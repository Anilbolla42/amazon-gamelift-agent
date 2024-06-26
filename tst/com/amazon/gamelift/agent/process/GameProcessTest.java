/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 */
package com.amazon.gamelift.agent.process;

import com.amazon.gamelift.agent.manager.ProcessEnvironmentManager;
import com.amazon.gamelift.agent.model.GameProcessConfiguration;
import com.amazon.gamelift.agent.model.OperatingSystem;
import com.amazon.gamelift.agent.model.ProcessStatus;
import com.amazon.gamelift.agent.model.exception.BadExecutablePathException;
import com.amazon.gamelift.agent.process.builder.ProcessBuilderWrapper;
import com.amazon.gamelift.agent.process.destroyer.ProcessDestroyer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class GameProcessTest {

    private static final GameProcessConfiguration PROCESS_CONFIG = GameProcessConfiguration.builder()
            .concurrentExecutions(1)
            .launchPath("testCommand")
            .parameters("--parameter1 --parameter2")
            .build();
    private static final OperatingSystem OPERATING_SYSTEM = OperatingSystem.DEFAULT_OS;

    @Mock private ProcessBuilderWrapper mockProcessBuilder;
    @Mock private Process mockProcess;
    @Mock private Map<String, String> mockEnvironmentVariableMap;
    @Mock private BiConsumer<Process, GameProcess> mockExitHandleFunction;
    @Mock private ProcessEnvironmentManager mockProcessEnvironmentManager;
    @Mock private ProcessDestroyer mockProcessDestroyer;

    private GameProcess processUnderTest;

    @BeforeEach
    public void setup() {
        processUnderTest = new GameProcess(PROCESS_CONFIG, mockProcessBuilder, mockProcessDestroyer,
                mockProcessEnvironmentManager, OPERATING_SYSTEM, Duration.ofSeconds(0));
    }

    @Test
    public void GIVEN_validProcessConfigurationWithParameters_WHEN_start_THEN_returnsProcessUuid() throws BadExecutablePathException {
        // GIVEN
        when(mockProcessEnvironmentManager.getProcessEnvironmentVariables(any())).thenReturn(mockEnvironmentVariableMap);
        when(mockProcessBuilder.buildProcess(mockEnvironmentVariableMap)).thenReturn(mockProcess);

        // WHEN
        String returnValue = processUnderTest.start();

        // THEN
        assertEquals(processUnderTest.getProcessUUID(), returnValue);
        verify(mockProcessEnvironmentManager).getProcessEnvironmentVariables(any());
        verify(mockProcessBuilder).buildProcess(mockEnvironmentVariableMap);
    }

    @Test
    public void GIVEN_processAlreadyStarted_WHEN_start_THEN_returnSameProcessId() throws BadExecutablePathException {
        // GIVEN
        when(mockProcessEnvironmentManager.getProcessEnvironmentVariables(any()))
                .thenReturn(mockEnvironmentVariableMap);
        when(mockProcessBuilder.buildProcess(any())).thenReturn(mockProcess);

        // WHEN
        String returnValue = processUnderTest.start();
        String secondReturnValue = processUnderTest.start();

        // THEN
        assertEquals(processUnderTest.getProcessUUID(), returnValue);
        assertEquals(processUnderTest.getProcessUUID(), secondReturnValue);
    }

    @Test
    public void GIVEN_processStarted_WHEN_terminate_THEN_delegateToProcessDestroyer() throws BadExecutablePathException {
        // GIVEN
        when(mockProcessEnvironmentManager.getProcessEnvironmentVariables(any()))
                .thenReturn(mockEnvironmentVariableMap);
        when(mockProcessBuilder.buildProcess(any())).thenReturn(mockProcess);

        // WHEN
        processUnderTest.start();
        processUnderTest.terminate();

        // THEN
        verify(mockProcessEnvironmentManager).getProcessEnvironmentVariables(any());
        verify(mockProcessBuilder).buildProcess(mockEnvironmentVariableMap);
        verify(mockProcessDestroyer).destroyProcess(mockProcess);
    }

    @Test
    public void GIVEN_processNotStarted_WHEN_terminate_THEN_doesNothing() {
        // GIVEN / WHEN
        processUnderTest.terminate();

        // THEN
        verifyNoInteractions(mockProcess);
    }

    @Test
    public void GIVEN_processStarted_WHEN_isAlive_THEN_returnsTrue() throws BadExecutablePathException {
        // GIVEN
        when(mockProcessEnvironmentManager.getProcessEnvironmentVariables(any()))
                .thenReturn(mockEnvironmentVariableMap);
        when(mockProcessBuilder.buildProcess(any())).thenReturn(mockProcess);
        when(mockProcess.isAlive()).thenReturn(true);

        // WHEN
        processUnderTest.start();

        // THEN
        assertTrue(processUnderTest.isAlive());
    }

    @Test
    public void GIVEN_processStartedButNotActive_WHEN_isAlive_THEN_returnsFalse() throws BadExecutablePathException {
        // GIVEN
        when(mockProcessEnvironmentManager.getProcessEnvironmentVariables(any()))
                .thenReturn(mockEnvironmentVariableMap);
        when(mockProcessBuilder.buildProcess(any())).thenReturn(mockProcess);
        when(mockProcess.isAlive()).thenReturn(false);

        // WHEN
        processUnderTest.start();

        // THEN
        assertFalse(processUnderTest.isAlive());
    }

    @Test
    public void GIVEN_processNotStarted_WHEN_isAlive_THEN_returnsTrue() {
        assertFalse(processUnderTest.isAlive());
    }

    @Test
    public void GIVEN_initializationDeadlinePassedForNewProcess_WHEN_hasTimedOutForInitialization_THEN_returnsTrue() {
        // GIVEN
        processUnderTest = new GameProcess(PROCESS_CONFIG, mockProcessBuilder, mockProcessDestroyer, mockProcessEnvironmentManager,
                OPERATING_SYSTEM, Duration.ofSeconds(-1));

        // WHEN / THEN
        assertTrue(processUnderTest.hasTimedOutForInitialization());
    }

    @Test
    public void GIVEN_initializationDeadlinePassedForActiveProcess_WHEN_hasTimedOutForInitialization_THEN_returnsFalse() {
        // GIVEN
        processUnderTest = new GameProcess(PROCESS_CONFIG, mockProcessBuilder, mockProcessDestroyer, mockProcessEnvironmentManager,
                OPERATING_SYSTEM, Duration.ofSeconds(-1));
        processUnderTest.setProcessStatus(ProcessStatus.Active);

        // WHEN / THEN
        assertFalse(processUnderTest.hasTimedOutForInitialization());
    }

    @Test
    public void GIVEN_initializationDeadlineNotPassedForNewProcess_WHEN_hasTimedOutForInitialization_THEN_returnsFalse() {
        // GIVEN
        processUnderTest = new GameProcess(PROCESS_CONFIG, mockProcessBuilder, mockProcessDestroyer, mockProcessEnvironmentManager,
                OPERATING_SYSTEM, Duration.ofSeconds(10));

        // WHEN / THEN
        assertFalse(processUnderTest.hasTimedOutForInitialization());
    }

    @Test
    public void GIVEN_processStarted_WHEN_handleProcessExit_THEN_attachesConsumerToExitFuture() throws BadExecutablePathException {
        // GIVEN
        CompletableFuture<Process> onProcessExitFuture = new CompletableFuture<>();
        when(mockProcessEnvironmentManager.getProcessEnvironmentVariables(any()))
                .thenReturn(mockEnvironmentVariableMap);
        when(mockProcessBuilder.buildProcess(any())).thenReturn(mockProcess);
        when(mockProcess.onExit()).thenReturn(onProcessExitFuture);
        processUnderTest.start();

        // WHEN
        processUnderTest.handleProcessExit(mockExitHandleFunction);
        onProcessExitFuture.complete(mockProcess);

        // THEN
        verify(mockExitHandleFunction).accept(mockProcess, processUnderTest);
    }

    @Test
    public void GIVEN_processNotStarted_WHEN_handleProcessExit_THEN_attachesConsumerToExitFuture() {
        // GIVEN

        // WHEN
        processUnderTest.handleProcessExit(mockExitHandleFunction);

        // THEN
        verifyNoInteractions(mockProcess);
    }

    @Test
    public void GIVEN_buildProcessThrowsBadExecutablePathException_WHEN_start_THEN_throwException() throws BadExecutablePathException {
        // GIVEN
        when(mockProcessEnvironmentManager.getProcessEnvironmentVariables(any()))
                .thenReturn(mockEnvironmentVariableMap);
        when(mockProcessBuilder.buildProcess(any())).thenThrow(BadExecutablePathException.class);

        // THEN
        assertThrows(BadExecutablePathException.class, () -> processUnderTest.start());
    }
}
