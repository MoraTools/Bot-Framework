package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.*;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VariableCardParityTest {
    @Test
    public void cardsAndCompressedSnapshotsKeepTheirExactHtml() throws Exception {
        Map<String, Value> values = snapshotValues();
        String expected = Files.readString(Path.of("src/test/resources/logs/variable-cards.html"));
        Assert.assertEquals(HTMLGenerator.generateVariablesHTML(values), expected);
        Assert.assertEquals(CompressedSnapshots.expand(HTMLGenerator.generateVariablesButton(values)), expected);
    }

    static Map<String, Value> snapshotValues() {
        Map<String, Value> dictionary = new LinkedHashMap<>();
        dictionary.put(null, null);
        dictionary.put("key<&>", new StringValue("dictionary\tvalue  "));
        DictionaryValue nestedDictionary = new DictionaryValue(dictionary);
        ListValue list = new ListValue();
        list.set(Arrays.asList(null, new StringValue("list<&>\n"), nestedDictionary));
        List<Schema> schema = List.of(new Schema("Column<&>"), new Schema("Null"));
        RecordValue record = new RecordValue(schema, Arrays.asList(list, null));
        TableValue table = new TableValue();
        table.set(new Table(schema, List.of(new Row(record, nestedDictionary), new Row(null, list))));

        Map<String, Value> values = new LinkedHashMap<>();
        values.put(null, null);
        values.put("text<&>", new StringValue("\" ' 日本語 <&>\r\n\tend  "));
        values.put("number", new NumberValue(42));
        values.put("boolean", new BooleanValue(true));
        values.put("date", new DateTimeValue(ZonedDateTime.parse("2026-09-30T12:00:00-05:00")));
        values.put("list", list);
        values.put("dictionary", nestedDictionary);
        values.put("record", record);
        values.put("table", table);
        return values;
    }
}
