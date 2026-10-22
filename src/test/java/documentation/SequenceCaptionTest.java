package documentation;

import com.automationanywhere.botcommand.actions.documentation.CautionSequenceCommand;
import com.automationanywhere.botcommand.actions.documentation.SequenceCommand;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.BooleanValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class SequenceCaptionTest {
    @Test
    public void testStepsSdkWrapperAcceptsAllTextCaptions() {
        Map<String, Value> parameters = new HashMap<>();
        parameters.put("sequenceTitle", new StringValue("Steps"));
        for (int i = 1; i <= 3; i++) {
            parameters.put("sequenceCheckBox" + i, new BooleanValue(true));
            parameters.put("sequenceImageText" + i, new StringValue("Caption " + i));
        }
        Assert.assertTrue(new SequenceCommand().execute(null, parameters, new HashMap<>()).isEmpty());
    }

    @Test
    public void testCautionStepsSdkWrapperAcceptsAllTextCaptions() {
        Map<String, Value> parameters = new HashMap<>();
        parameters.put("cautionSequenceTitle", new StringValue("Caution Steps"));
        for (int i = 1; i <= 3; i++) {
            parameters.put("cautionSequenceCheckBox" + i, new BooleanValue(true));
            parameters.put("cautionSequenceText" + i, new StringValue("Caption " + i));
        }
        Assert.assertTrue(new CautionSequenceCommand().execute(null, parameters, new HashMap<>()).isEmpty());
    }
}
