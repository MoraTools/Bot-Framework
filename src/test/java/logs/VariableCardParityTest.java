package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.*;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VariableCardParityTest {
    @Test
    public void cardsAndCompressedSnapshotsKeepTheirStructureAndEveryValue() throws Exception {
        Map<String, Value> values = snapshotValues();
        String expected = Files.readString(Path.of("src/test/resources/logs/variable-cards.html"));
        String actual = HTMLGenerator.generateVariablesHTML(values);
        Document oldCards = parse(expected);
        Document newCards = parse(actual);
        removeStructuralNewlines(oldCards.getDocumentElement());
        Assert.assertTrue(oldCards.isEqualNode(newCards), "all elements, attributes and meaningful text match the old snapshot");
        Assert.assertEquals(CompressedSnapshots.expand(HTMLGenerator.generateVariablesButton(values)), actual);
        Assert.assertTrue(actual.length() < expected.length(), "structural formatting is omitted at the emitter");
    }

    @Test
    public void namesHeadersAndScalarWhitespaceSurviveEveryContainer() throws Exception {
        String name = " name<&>\r\n\t  ";
        String text = " \t日本語 \" ' <&>\r\nline  \n\tonly\rCR  ";
        StringValue string = new StringValue(text);
        CredentialObject credential = new CredentialObject(text);
        ListValue list = new ListValue();
        list.set(List.of(string, credential));
        Map<String, Value> dictionary = new LinkedHashMap<>();
        dictionary.put(name, string);
        dictionary.put("credential", credential);
        List<Schema> schema = List.of(new Schema(name), new Schema("credential"));
        RecordValue record = new RecordValue(schema, List.of(string, credential));
        TableValue table = new TableValue();
        table.set(new Table(schema, List.of(new Row(string, credential))));
        Map<String, Value> values = new LinkedHashMap<>();
        values.put(name, string);
        values.put("credential", credential);
        values.put("list", list);
        values.put("dictionary", new DictionaryValue(dictionary));
        values.put("record", record);
        values.put("table", table);

        String html = HTMLGenerator.generateVariablesHTML(values);
        Document cards = parse(CompressedSnapshots.expand(HTMLGenerator.generateVariablesButton(values)));
        Assert.assertEquals(CompressedSnapshots.expand(HTMLGenerator.generateVariablesButton(values)), html);
        Assert.assertEquals(texts(cards, "div", "variable-name"),
                List.of(name, "credential", "list", "Index 0", "Index 1", "dictionary", name,
                        "credential", "record", "table"));
        Assert.assertEquals(texts(cards, "th", null), List.of(name, "credential", name, "credential"));
        Assert.assertEquals(texts(cards, "div", "variable-type"),
                List.of("STRING", "CREDENTIAL", "LIST", "STRING", "CREDENTIAL", "DICTIONARY",
                        "STRING", "CREDENTIAL", "RECORD", "TABLE"));
        NodeList divs = cards.getElementsByTagName("div");
        List<String> scalars = new ArrayList<>();
        for (int i = 0; i < divs.getLength(); i++) {
            Element div = (Element) divs.item(i);
            if ("variable-value".equals(div.getAttribute("class"))) {
                scalars.add(decodedScalar(div));
            }
        }
        Assert.assertEquals(scalars, java.util.Collections.nCopies(10, text),
                "all scalar values retain CR, LF, tabs, leading/interior/trailing spaces and escapes");
    }

    private static Document parse(String html) throws Exception {
        // XML parsing normalizes literal CR; character references retain the exact scalar text.
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(
                new StringReader("<root>" + html.replace("\r", "&#13;") + "</root>")));
    }

    private static void removeStructuralNewlines(Node node) {
        boolean block = false;
        if (node instanceof Element) {
            Element element = (Element) node;
            String cssClass = element.getAttribute("class");
            if (cssClass.equals("variable-value") || cssClass.equals("variable-name")
                    || cssClass.equals("variable-type") || element.getTagName().equals("th")
                    || element.getTagName().equals("summary")) return;
            block = List.of("root", "details", "table", "tr", "td").contains(element.getTagName())
                    || List.of("variable-list", "variable-card", "nested-content", "table-container")
                    .contains(cssClass);
        }
        for (Node child = node.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            if (block && child.getNodeType() == Node.TEXT_NODE && child.getNodeValue().equals("\n")) {
                if (child.getPreviousSibling() instanceof Element && next instanceof Element
                        && ((Element) child.getPreviousSibling()).getAttribute("class").equals("variable-name")
                        && ((Element) next).getAttribute("class").equals("variable-type")) {
                    child.setNodeValue(" ");
                } else {
                    node.removeChild(child);
                }
            } else if (node.getNodeName().equals("td") && child.getNodeType() == Node.TEXT_NODE
                    && child.getNodeValue().equals("\nNULL")) {
                child.setNodeValue("NULL");
            } else {
                removeStructuralNewlines(child);
            }
            child = next;
        }
    }

    private static List<String> texts(Document cards, String tag, String cssClass) {
        NodeList nodes = cards.getElementsByTagName(tag);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (cssClass == null || cssClass.equals(element.getAttribute("class"))) {
                result.add(element.getTextContent());
            }
        }
        return result;
    }

    private static String decodedScalar(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE) return node.getNodeValue();
        if (node instanceof Element && ((Element) node).getAttribute("class").startsWith("ws-marker")) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            text.append(decodedScalar(child));
        }
        return text.toString();
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
