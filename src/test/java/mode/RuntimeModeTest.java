package mode;

import com.automationanywhere.botcommand.actions.mode.GetRuntimeModeCommand;
import com.automationanywhere.botcommand.actions.mode.IsRuntimeModeCondition;
import com.automationanywhere.botcommand.actions.mode.StartRuntimeModeCommand;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.SessionValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utilities.mode.RuntimeMode;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class RuntimeModeTest {
    @DataProvider
    public Object[][] validModes() {
        return new Object[][]{{"DEV", "DEV"}, {" qa ", "QA"}, {"\tPrd\n", "PRD"}};
    }

    @Test(dataProvider = "validModes")
    public void startStoresUppercaseMode(String input, String expected) {
        SessionValue value = start(input);
        Assert.assertTrue(value.hasObjectValue());
        Assert.assertEquals(((RuntimeMode) value.getSession()).getMode(), expected);
    }

    @Test
    public void startRejectsOtherModesAndNamesAllowedValues() {
        BotCommandException error = Assert.expectThrows(BotCommandException.class, () -> start("UAT"));
        Assert.assertTrue(error.getMessage().contains("DEV, QA or PRD"), error.getMessage());
    }

    @Test
    public void getReadsNamedSession() {
        Map<String, Object> sessions = new HashMap<>();
        sessions.put("runtimeMode", start("qa").getSession());
        Assert.assertEquals(get("runtimeMode", "DEV", sessions).get(), "QA");
    }

    @Test
    public void getUsesValidatedDefaultWithoutSession() {
        Assert.assertEquals(get("runtimeMode", " prd ", new HashMap<>()).get(), "PRD");
        BotCommandException error = Assert.expectThrows(BotCommandException.class,
                () -> get("runtimeMode", "test", new HashMap<>()));
        Assert.assertTrue(error.getMessage().contains("DEV, QA or PRD"), error.getMessage());
    }

    @Test
    public void isComparesSessionMode() {
        Map<String, Object> sessions = new HashMap<>();
        sessions.put("runtimeMode", start("PRD").getSession());
        Assert.assertTrue(is("PRD", "DEV", sessions));
        Assert.assertFalse(is("DEV", "DEV", sessions));
    }

    @Test
    public void isUsesDefaultWithoutSession() {
        Assert.assertTrue(is("QA", " qa ", new HashMap<>()));
        Assert.assertFalse(is("PRD", "QA", new HashMap<>()));
    }

    private static SessionValue start(String mode) {
        Map<String, Value> parameters = new HashMap<>();
        parameters.put("mode", new StringValue(mode));
        return (SessionValue) new StartRuntimeModeCommand().execute(null, parameters, new HashMap<>()).get();
    }

    private static StringValue get(String sessionName, String defaultMode, Map<String, Object> sessions) {
        return (StringValue) new GetRuntimeModeCommand().execute(null, parameters(sessionName, defaultMode), sessions).get();
    }

    private static boolean is(String mode, String defaultMode, Map<String, Object> sessions) {
        Map<String, Value> parameters = parameters("runtimeMode", defaultMode);
        parameters.put("mode", new StringValue(mode));
        return new IsRuntimeModeCondition().test(null, parameters, sessions);
    }

    private static Map<String, Value> parameters(String sessionName, String defaultMode) {
        Map<String, Value> parameters = new HashMap<>();
        parameters.put("session", new SessionValue(sessionName));
        parameters.put("defaultMode", new StringValue(defaultMode));
        return parameters;
    }
}
