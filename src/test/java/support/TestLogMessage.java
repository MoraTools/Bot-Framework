package support;

import com.automationanywhere.botcommand.actions.logs.LogMessage;

/** A Log Message action that runs outside a bot: the Source column gets a fixed bot path. */
public final class TestLogMessage {
    private TestLogMessage() { }

    public static LogMessage create() {
        LogMessage action = new LogMessage();
        action.setTestBotUri("Automation Anywhere/bots/test/logger-test");
        return action;
    }
}
