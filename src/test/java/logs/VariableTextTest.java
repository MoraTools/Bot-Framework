package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.BooleanValue;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.ListValue;
import com.automationanywhere.botcommand.data.impl.NumberValue;
import com.automationanywhere.botcommand.data.impl.RecordValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.data.impl.TableValue;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.helios.VariableText;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author jamir-boop
 */
public class VariableTextTest {

    private static ListValue list(Value... items) {
        ListValue listValue = new ListValue();
        listValue.set(Arrays.asList(items));
        return listValue;
    }

    @Test
    public void scalarsRenderAsTheirOwnText() {
        Assert.assertEquals(VariableText.render(new StringValue("INV-001")), "INV-001");
        Assert.assertEquals(VariableText.render(new NumberValue(42)), "42.0");
        Assert.assertEquals(VariableText.render(new BooleanValue(true)), "true");
    }

    @Test
    public void nullRendersAsNull() {
        Assert.assertEquals(VariableText.render(null), "NULL");
    }

    @Test
    public void listRendersAsAJsonArrayKeepingItemTypes() {
        JSONArray array = new JSONArray(VariableText.render(
                list(new StringValue("a"), new NumberValue(7), new BooleanValue(false), null)));

        Assert.assertEquals(array.length(), 4);
        Assert.assertEquals(array.getString(0), "a");
        Assert.assertEquals(array.getDouble(1), 7.0, 0.0);
        Assert.assertFalse(array.getBoolean(2));
        Assert.assertTrue(array.isNull(3));
    }

    @Test
    public void dictionaryRendersAsAJsonObjectAndNestsRecursively() {
        Map<String, Value> inner = new LinkedHashMap<>();
        inner.put("city", new StringValue("Lima"));

        Map<String, Value> outer = new LinkedHashMap<>();
        outer.put("name", new StringValue("Alice"));
        outer.put("scores", list(new NumberValue(1), new NumberValue(2)));
        outer.put("address", new DictionaryValue(inner));

        JSONObject object = new JSONObject(VariableText.render(new DictionaryValue(outer)));

        Assert.assertEquals(object.getString("name"), "Alice");
        Assert.assertEquals(object.getJSONArray("scores").getDouble(1), 2.0, 0.0);
        Assert.assertEquals(object.getJSONObject("address").getString("city"), "Lima");
    }

    @Test
    public void recordRendersAsAJsonObjectKeyedByColumnName() {
        RecordValue record = new RecordValue(
                Arrays.asList(new Schema("id"), new Schema("paid")),
                Arrays.asList(new StringValue("INV-001"), new BooleanValue(true)));

        JSONObject object = new JSONObject(VariableText.render(record));

        Assert.assertEquals(object.length(), 2);
        Assert.assertEquals(object.getString("id"), "INV-001");
        Assert.assertTrue(object.getBoolean("paid"));
    }

    @Test
    public void tableRendersAsAJsonArrayOfRowObjects() {
        TableValue table = new TableValue();
        table.set(new Table(
                Arrays.asList(new Schema("id"), new Schema("amount")),
                Arrays.asList(
                        new Row(new StringValue("INV-001"), new NumberValue(10)),
                        new Row(new StringValue("INV-002"), new NumberValue(20)))));

        JSONArray rows = new JSONArray(VariableText.render(table));

        Assert.assertEquals(rows.length(), 2);
        Assert.assertEquals(rows.getJSONObject(0).getString("id"), "INV-001");
        Assert.assertEquals(rows.getJSONObject(1).getDouble("amount"), 20.0, 0.0);
    }

    @Test
    public void renderingNeverThrows() {
        Assert.assertEquals(VariableText.render(new ExplodingValue()), "<unrenderable EXPLODING>",
                "one bad variable must not cost the entry");
    }

    /** Stands in for an SDK value the renderer cannot walk. */
    private static class ExplodingValue extends StringValue {
        @Override
        public String get() {
            throw new IllegalStateException("boom");
        }
    }
}
