/*
    Copyright (C) 2010 LearningWell AB (www.learningwell.com), Kärnkraftsäkerhet och Utbildning AB (www.ksu.se)

    This file is part of GIL (Generic Integration Layer).

    GIL is free software: you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GIL is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with GIL.  If not, see <http://www.gnu.org/licenses/>.
*/package gil.core;

import java.util.concurrent.ExecutionException;
import gil.common.IProgressEventListener;
import gil.common.ProgressChangedEventArgs;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import gil.io.*;
import gil.common.CurrentTime;
import gil.common.GILConfiguration;

/**
 * This class manages the data and command interchange between the process model and an external system.
 *
 * @author Göran Larsson @ LearningWell AB
 */
public class IntegrationExecutive {    
    private volatile int _externalSystemState = SimState.UNKNOWN;
    private volatile SystemStatus _externalSystemStatus = new SystemStatus(SystemStatus.UNKNOWN, "");
    private volatile int _processModelState = SimState.UNKNOWN;
    private volatile SystemStatus _processModelStatus = new SystemStatus(SystemStatus.UNKNOWN, "");
    private volatile ProgressChangedEventArgs _currentESActivity = new ProgressChangedEventArgs(0, "Not available", true);
    private volatile ProgressChangedEventArgs _currentPMActivity = new ProgressChangedEventArgs(0, "Not available", true);
    private volatile boolean _stopESThread;
    private volatile boolean _stopPMThread;

    private Thread _externalSystemThread;
    private Thread _processModelThread;
    private IExternalSystemAdapter _externalSystem;
    private IProcessModelAdapter _processModel;
    private ExternalSystemProcedure _externalSystemProcedure;
    private ProcessModelProcedure _processModelProcedure;
    private static Logger _logger = Logger.getLogger(IntegrationExecutive.class);
    private ITransferPipeline _pipeline;
    private AdapterValueObject _esAdapterVO;
    private AdapterValueObject _pmAdapterVO;


    /**
     * Initializes this object with the three parts forming an integration solution. The external system adapter
     * to be the front end facing an arbitrary external system. The process model adapter to be the front end
     * to the process model. The transfer pipeline used to transform and monitor data flowing between the external
     * system and the process model.
     */
    public IntegrationExecutive(IProcessModelAdapter pm, IExternalSystemAdapter es, ITransferPipeline pipe,
            SignalMetadata[] smd, GILConfiguration config) {
        _externalSystem = es;
        _processModel = pm;
        IntegrationContext context = new IntegrationContext();
        _processModelProcedure = new ProcessModelProcedure(_processModel,
                    es.getOperatingFrequency(), pipe, context,
                    SignalMetadata.calcBufferSize(SignalMetadata.getSignalsToExternalSystem(smd)), config);
        _externalSystemProcedure = new ExternalSystemProcedure(_externalSystem, context,
                SignalMetadata.calcBufferSize(SignalMetadata.getSignalsToProcessModel(smd)), config);

        _externalSystemState = _externalSystem.getState();        
        _externalSystemStatus = _externalSystem.getStatus();
        _processModelState = _processModel.getState();
        _processModelStatus = _processModel.getStatus();
        _pipeline = pipe;

        if (_externalSystem.getCapabilities().reportsProgress) {
            _externalSystem.addProgressChangeListener(new IProgressEventListener() {
                public void progressChanged(ProgressChangedEventArgs args) {
                    _currentESActivity = args;
                }
            });
        }

        _processModel.addProgressChangeListener(new IProgressEventListener() {
            public void progressChanged(ProgressChangedEventArgs args) {
                _currentPMActivity = args;
            }
        });

        _esAdapterVO = createESAdapterValueObject();
        _pmAdapterVO = createPMAdapterValueObject();
    }

    /**
     * Starts the data supervision and data transfer between the process models and the external system.
     * If allready started this method does nothing.
     */
    public void start() throws IOException {        
        _externalSystemThread = new Thread(_esRunnable);
        _processModelThread = new Thread(_pmRunnable);
        _stopESThread = false;
        _stopPMThread = false;        
        _logger.info("Integration executive started");
        _externalSystemThread.start();
        _processModelThread.start();
    }

    public void stop() throws IOException {
        _stopESThread = true;
        _stopPMThread = true;

        try {
            _externalSystemThread.join(10000);
            _processModelThread.join(10000);
        }
        catch (InterruptedException e) {}

        _externalSystemThread = null;
        _processModelThread = null;
        _externalSystem.disconnect();
        _processModel.disconnect();
    }

    private Runnable _esRunnable = new Runnable() {
        public void run() {
            _logger.debug("External system thread started");
            _logger.info("ProcessModel operating frequency: " + _processModel.getOperatingFrequency());
            _logger.info("ExternalSystem operating frequency: " + _externalSystem.getOperatingFrequency());            
            try
            {
                while(!_stopESThread) {
                    _externalSystemProcedure.runOnce(CurrentTime.instance().inMilliseconds());
                    _externalSystemState = _externalSystemProcedure.getExternalSystemState();
                    _externalSystemStatus = _externalSystemProcedure.getExternalSystemStatus();
                    Thread.sleep(1);
                }
            }
            catch(Exception e) {
                _logger.fatal("Unexpected failure in external system thread", e);
            }
            _logger.debug("External system thread stopped");
        }
    };

    private Runnable _pmRunnable = new Runnable() {
        public void run() {
            _logger.debug("Process model thread started");
            try
            {
                while(!_stopPMThread) {
                    _processModelProcedure.runOnce(CurrentTime.instance().inMilliseconds());
                    _processModelState = _processModelProcedure.getProcessModelState();
                    _processModelStatus = _processModelProcedure.getProcessModelStatus();
                    Thread.sleep(1);
                }
            }
            catch(Exception e) {
                _logger.fatal("Unexpected failure in process model thread", e);
            }
            _logger.debug("Process model thread stopped");
        }
    };

    public Statistics getExternalSystemStatistics() {
        return new Statistics(_processModelProcedure.getDroppedExternalSystemFrames(),
                _externalSystemProcedure.getCommandWriteFailureCount(),
                _externalSystemProcedure.getDataWriteFailureCount(),
                _externalSystemProcedure.getDataReadFailureCount());
    }

    public Statistics getProcessModelStatistics() {
        return new Statistics(_processModelProcedure.getDroppedProcessModelFrames()
                + _externalSystemProcedure.getDroppedProcessModelFrames(),
                _processModelProcedure.getCommandReadFailureCount()
                + _processModelProcedure.getSimTimeReadFailureCount(),
                _processModelProcedure.getDataWriteFailureCount(),
                _processModelProcedure.getDataReadFailureCount());
    }

    public int getExternalSystemState() {
        return _externalSystemState;
    }

    public SystemStatus getExternalSystemStatus() {
        return _externalSystemStatus;
    }

    public int getProcessModelState() {
        return _processModelState;
    }

    public SystemStatus getProcessModelStatus() {
        return _processModelStatus;
    }

    public ProgressChangedEventArgs getExternalSystemActivity() {
            return _currentESActivity;
    }

    public ProgressChangedEventArgs getProcessModelActivity() {
            return _currentPMActivity;
    }
    
    public StageValueObject[] getTransferPipelineStages() {
        
        List<IPipelineStage> stages = _pipeline.getStages();
        StageValueObject[] sv = new StageValueObject[stages.size()];
        int i = 0;
        for (IPipelineStage stage: stages) {

            if (stage.availableCommands() == null) {
                throw new NullPointerException(
                        String.format("Expected stage %s to return available commands.", stage.getClass().getName()));
            }

            sv[i] = new StageValueObject(i, stage.getClass().getSimpleName(), stage.availableCommands());
            i++;
        }                
        return sv;
    }

    public AdapterValueObject getExternalSystemAdapter() {
        return _esAdapterVO;
    }

    public AdapterValueObject getProcessModelAdapter() {
        return _pmAdapterVO;
    }

    /**
     * Invokes the control command with the given command ID on the requested stage.
     * This method blocks until the command is completed.
     * @param stageSeqNo The order no. of the stage in the transfer pipeline that uniquely identifies it.
     * @param commandID The unique (within one stage class) identifier for the command to be invoked.
     * @param parameters The map of parameters where the key identifies the parameter.
     * @return A map of result values from the invokation.
     * @throws InterruptedException Thrown if the invokation is interrupted for some reason.
     * @throws ExecutionException Thrown if an exception is thrown by the underlying pipeline stage during the execution
     * of the command.
     */
    public Map<String, String> invokePipelineStageCommand(int stageSeqNo, String commandID, Map<String, String> parameters)
            throws InterruptedException, ExecutionException {
        return _processModelProcedure.invokePipelineStageControlCommand(stageSeqNo, commandID, parameters);
    }

    /**
     * Invokes the control command with the given command ID on external system.
     * This method blocks until the command is completed.
     * @param commandID The unique identifier for the command to be invoked.
     * @param parameters The map of parameters where the key identifies the parameter.
     * @return A map of result values from the invokation.
     * @throws InterruptedException Thrown if the invokation is interrupted for some reason.
     * @throws ExecutionException Thrown if an exception is thrown by the underlying adapter during the execution
     * of the command.
     */
    public Map<String, String> invokeExternalSystemCommand(String commandID, Map<String, String> parameters) 
            throws InterruptedException, ExecutionException {
        return _externalSystemProcedure.invokeControlCommand(commandID, parameters);        
    }
    
    /**
     * Invokes the control command with the given command ID on the process model.
     * This method blocks until the command is completed.
     * @param commandID The unique identifier for the command to be invoked.
     * @param parameters The map of parameters where the key identifies the parameter.
     * @return A map of result values from the invokation.
     * @throws InterruptedException Thrown if the invokation is interrupted for some reason.
     * @throws ExecutionException Thrown if an exception is thrown by the underlying adapter during the execution
     * of the command.
     */
    public Map<String, String> invokeProcessModelCommand(String commandID, Map<String, String> parameters)
            throws InterruptedException, ExecutionException {
        return _processModelProcedure.invokeControlCommand(commandID, parameters);
    }

    private AdapterValueObject createESAdapterValueObject() {
        CommandDescriptor[] cmds = _externalSystem.availableControlCommands();
        CommandDescriptor[] cmds2 = new CommandDescriptor[cmds.length + 1];
        System.arraycopy(cmds, 0, cmds2, 1, cmds.length);
        cmds2[0] = new CommandDescriptor("reconnect", "Disconnects (if connected) from the external system and tries to connect.");
        return new AdapterValueObject(_externalSystem.getClass().getSimpleName(), cmds2);
    }

    private AdapterValueObject createPMAdapterValueObject() {
        CommandDescriptor[] cmds = _processModel.availableControlCommands();
        CommandDescriptor[] cmds2 = new CommandDescriptor[cmds.length + 1];
        System.arraycopy(cmds, 0, cmds2, 1, cmds.length);
        cmds2[0] = new CommandDescriptor("reconnect", "Disconnects (if connected) from the process model and tries to connect.");
        return new AdapterValueObject(_processModel.getClass().getSimpleName(), cmds2);
    }

}
