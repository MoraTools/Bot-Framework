package com.automationanywhere.botcommand.utilities.helios;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.ListValue;
import com.automationanywhere.botcommand.data.impl.RecordValue;
import com.automationanywhere.botcommand.data.impl.TableValue;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.record.Record;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Renders a bot {@link Value} as the plain text that Helios Cloud shows for a streamed variable.
 *
 * <p>Scalars render as their own text, the same string the variables HTML page shows. Containers
 * render as JSON text: a list becomes an array, a dictionary and a record become objects, and a
 * table becomes an array of row objects keyed by column name. Nested values follow the same rules.
 *
 * <p>Rendering never throws: a value the SDK cannot walk renders as {@code <unrenderable TYPE>} so
 * one bad variable cannot cost the entry.
 *
 * @author jamir-boop
 */
public final class VariableText {

    private VariableText() {
    }

    /** @return the plain-text rendering of {@code value}; {@code NULL} when it is null. */
    public static String render(Value value) {
        try {
            Object json = toJson(value);
            if (json instanceof JSONObject || json instanceof JSONArray) {
                return json.toString();
            }
            return json == JSONObject.NULL ? "NULL" : String.valueOf(json);
        } catch (Exception | StackOverflowError e) {
            return "<unrenderable " + HTMLGenerator.typeLabel(value) + ">";
        }
    }

    /** Maps a value onto the org.json object model; scalars keep their JSON type. */
    @SuppressWarnings("rawtypes")
    private static Object toJson(Value value) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof ListValue) {
            JSONArray array = new JSONArray();
            List<Value> items = ((ListValue) value).get();
            for (Value item : items) {
                array.put(toJson(item));
            }
            return array;
        }
        if (value instanceof DictionaryValue) {
            JSONObject object = new JSONObject();
            for (Map.Entry<String, Value> entry : ((DictionaryValue) value).get().entrySet()) {
                object.put(entry.getKey(), toJson(entry.getValue()));
            }
            return object;
        }
        if (value instanceof RecordValue) {
            Record record = ((RecordValue) value).get();
            return rowJson(record.getSchema(), record.getValues());
        }
        if (value instanceof TableValue) {
            Table table = ((TableValue) value).get();
            JSONArray rows = new JSONArray();
            for (Row row : table.getRows()) {
                rows.put(rowJson(table.getSchema(), row.getValues()));
            }
            return rows;
        }
        Object raw = value.get();
        if (raw == null) {
            return JSONObject.NULL;
        }
        return (raw instanceof Number || raw instanceof Boolean) ? raw : raw.toString();
    }

    /** One record or table row as an object keyed by column name; the index names extra cells. */
    private static JSONObject rowJson(List<Schema> schema, List<Value> values) {
        JSONObject object = new JSONObject();
        for (int i = 0; i < values.size(); i++) {
            object.put(i < schema.size() ? schema.get(i).getName() : String.valueOf(i), toJson(values.get(i)));
        }
        return object;
    }
}
