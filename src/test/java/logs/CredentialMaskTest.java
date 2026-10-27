package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.CredentialObject;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.ListValue;
import com.automationanywhere.botcommand.data.impl.RecordValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.data.impl.TableValue;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.helios.VariableText;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import support.LogEntries;

import java.util.List;
import java.util.Map;

public class CredentialMaskTest {
    private static final String SECRET = "hunter2-secret";

    @DataProvider
    public Object[][] credentials() {
        CredentialObject credential = new CredentialObject(SECRET);
        ListValue list = new ListValue();
        list.set(List.of(credential, new StringValue("plain text")));
        List<Schema> schema = List.of(new Schema("credential"));
        TableValue table = new TableValue();
        table.set(new Table(schema, List.of(new Row(credential))));
        return new Object[][] {
                {"top-level", credential, 1},
                {"empty", new CredentialObject(""), 1},
                {"list", list, 1},
                {"dictionary", new DictionaryValue(Map.of("credential", credential)), 1},
                {"record", new RecordValue(schema, List.of(credential)), 1},
                {"table", table, 1},
                {"dictionary-list", new DictionaryValue(Map.of("list", list)), 1}
        };
    }

    @Test(dataProvider = "credentials")
    public void htmlLogMasksEveryCredential(String name, Value value, int credentialCount) throws Exception {
        Map<String, Value> variables = Map.of(name, value);
        assertMasked(HTMLGenerator.generateVariablesHTML(variables), credentialCount, name + " HTML");
        assertMasked(CompressedSnapshots.expand(HTMLGenerator.generateVariablesButton(variables)),
                credentialCount, name + " compressed HTML");
    }

    @Test(dataProvider = "credentials")
    public void heliosTextMasksEveryCredential(String name, Value value, int credentialCount) {
        assertMasked(VariableText.render(value), credentialCount, name + " Helios");
    }

    private static void assertMasked(String text, int credentialCount, String context) {
        Assert.assertEquals(LogEntries.occurrences(text, "********"), credentialCount, context + " mask count");
        Assert.assertFalse(text.contains(SECRET), context + " must not contain the secret");
        Assert.assertFalse(text.contains("SecureString"), context + " must not contain a SecureString identity");
    }
}
