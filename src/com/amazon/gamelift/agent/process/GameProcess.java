/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 */
package com.amazon.gamelift.agent.process;

import com.amazon.gamelift.agent.model.GameProcessConfiguration;
import com.amazon.gamelift.agent.model.OperatingSystem;
import com.amazon.gamelift.agent.model.ProcessStatus;
import com.amazon.gamelift.agent.model.ProcessTerminationReason;
import com.amazon.gamelift.agent.model.exception.BadExecutablePathException;
import com.amazon.gamelift.agent.process.builder.ProcessBuilderFactory;
import com.amazon.gamelift.agent.process.builder.ProcessBuilderWrapper;
import com.amazon.gamelift.agent.manager.ProcessEnvironmentManager;
import com.amazon.gamelift.agent.process.destroyer.ProcessDestroyer;
import com.amazon.gamelift.agent.process.destroyer.ProcessDestroyerFactory;
import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * GameProcess is a wrapper around the Java 9 Process that a customer's game server build
 * is running on. It handles using a GameProcessConfiguration to initialize the process, and
 * provides an interface to the Process during its lifecycle.
 */
@Slf4j
public class GameProcess {

    // Default timeout value to wait for a process to go from Initializing -> Active before it gets force terminated
    private static final Duration DEFAULT_INITIALIZATION_TIMEOUT = Duration.ofMinutes(5);

    // This is a unique identifier for the process, generated by the GameLiftAgent and shared with
    // the customer's build and with Amazon GameLift services. This is not the PID from the Compute System -
    // that can be accessed from internalProcess.pid()
    @Getter private final String processUUID;
    @Getter private final GameProcessConfiguration processConfiguration;
    private final ProcessBuilderWrapper processBuilderWrapper;
    private final ProcessDestroyer processDestroyer;
    private final ProcessEnvironmentManager processEnvironmentManager;
    private final Instant initializationTimeoutDeadline;

    private Process internalProcess;

    // Reason field which may be set before the process is terminated to indicate why the termination occurred
    @Getter @Setter private ProcessTerminationReason terminationReason;

    // Status field indicating the current state of where the process is at in it's lifecycle
    @Getter @Setter @NonNull private ProcessStatus processStatus;

    // A set of file paths that should be set by the process when it registers with the GameLift SDK,
    // and may be uploaded when this process terminates.
    @Getter private Set<String> logPaths;
    // This field is set in response to a NotifyGameSessionActivatedMessage message sent by Amazon GameLift when a game
    // session is activated on a server process
    @Getter @Setter private String gameSessionId;

    /**
     * Constructor for GameProcess
     * @param processConfiguration
     * @param processEnvironmentManager
     * @param operatingSystem
     */
    public GameProcess(final GameProcessConfiguration processConfiguration,
                       final ProcessEnvironmentManager processEnvironmentManager,
                       final OperatingSystem operatingSystem) {
        this(processConfiguration,
             ProcessBuilderFactory.getProcessBuilder(processConfiguration, operatingSystem),
             ProcessDestroyerFactory.getProcessDestroyer(operatingSystem),
             processEnvironmentManager,
             operatingSystem,
             DEFAULT_INITIALIZATION_TIMEOUT);
    }

    @VisibleForTesting GameProcess(final GameProcessConfiguration processConfiguration,
                                   final ProcessBuilderWrapper processBuilderWrapper,
                                   final ProcessDestroyer processDestroyer,
                                   final ProcessEnvironmentManager processEnvironmentManager,
                                   final OperatingSystem operatingSystem,
                                   final Duration initializationTimeout) {
        this.processConfiguration = processConfiguration;
        this.processBuilderWrapper = processBuilderWrapper;
        this.processDestroyer = processDestroyer;
        this.processEnvironmentManager = processEnvironmentManager;
        this.processUUID = UUID.randomUUID().toString();
        this.logPaths = new HashSet<String>();
        this.processStatus = ProcessStatus.Initializing;
        this.initializationTimeoutDeadline = Instant.now().plus(initializationTimeout);
    }

    /**
     * If internalProcess is not already set, this method will construct a Java Process
     * using the GameProcessConfiguration provided in the constructor in order to startup
     * a customer's game server process. The internalProcess field will be set to the Process
     * object if initialization is successful.
     *
     * @return Long representing the process ID of the initialized game process
     */
    public String start() throws BadExecutablePathException {
        if (internalProcess != null) {
            log.error("Attempted to start a game process that has already been launched. Configuration = [{}]",
                    processConfiguration);
            return processUUID;
        }

        final Map<String, String> environmentVariables = processEnvironmentManager
                .getProcessEnvironmentVariables(processUUID);
        log.info("Starting process from configuration [{}] with env vars [{}]", processConfiguration,
                processEnvironmentManager.getPrintableEnvironmentVariables(processUUID));
        this.internalProcess = processBuilderWrapper.buildProcess(environmentVariables);
        return processUUID;
    }

    /**
     * Forcibly terminates the game process. Process should normally tear down on their own,
     * so the GameLiftAgent should only need to call this to force the termination.
     */
    public void terminate() {
        if (internalProcess != null) {
            log.info("Terminating process with ID {}", internalProcess.pid());
            processDestroyer.destroyProcess(internalProcess);
        } else {
            log.warn("Attempted to terminate a process that hasn't been initialized");
        }
    }

    /**
     * Defer to the internal Process model to check if the process is still active
     * @return boolean representing if the process is still running; returns false if process was never started
     */
    public boolean isAlive() {
        return internalProcess != null && internalProcess.isAlive();
    }

    /**
     * Helper method to check if the process is still in the Initializing state after the timeout has passed
     * @return true if the process is Initializing and the timeout value has passed
     */
    public boolean hasTimedOutForInitialization() {
        return ProcessStatus.Initializing.equals(processStatus)
                && Instant.now().isAfter(initializationTimeoutDeadline);
    }

    /**
     * Attaches a method to the internal process' onExit() future so that we can perform cleanup/reporting tasks
     * when the process exits.
     */
    public void handleProcessExit(BiConsumer<Process, GameProcess> onExitMethod) {
        if (internalProcess != null) {
            internalProcess.onExit().thenAccept(process -> onExitMethod.accept(process, this));
        } else {
            log.warn("Attempted to handle process exit for a process that hasn't been initialized");
        }
    }

    /**
     * Setter for LogPaths to ensure the collection is null-safe, and also converts from list
     * to remove duplicate log files.
     * @param logPaths the collection of paths to set for this process
     */
    public void setLogPaths(final List<String> logPaths) {
        if (logPaths == null) {
            this.logPaths = new HashSet<String>();
        } else {
            this.logPaths = new HashSet<String>(logPaths);
        }
    }
}
