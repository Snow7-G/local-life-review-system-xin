package dev.lifeagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema generation, checked against the 手册 §5.3 fixture field by field.
 */
class JsonSchemaGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generatesTheFixtureSchemaForSearchNearbyArgs() {
        ObjectNode schema = JsonSchemaGenerator.schemaFor(SearchNearbyArgs.class);

        assertEquals("object", schema.get("type").asText());
        assertEquals(List.of("category", "radius", "maxPrice"), fieldNames(schema.get("properties")));

        JsonNode category = schema.get("properties").get("category");
        assertEquals("string", category.get("type").asText());
        assertEquals("品类，比如火锅、KTV", category.get("description").asText());

        JsonNode radius = schema.get("properties").get("radius");
        assertEquals("integer", radius.get("type").asText());
        assertEquals("半径，单位米，最大 5000", radius.get("description").asText());

        // The wrapper type makes maxPrice optional: it can hold null, so the model may omit it.
        JsonNode maxPrice = schema.get("properties").get("maxPrice");
        assertEquals("integer", maxPrice.get("type").asText());
        assertEquals("人均预算上限，单位元，可不填", maxPrice.get("description").asText());

        assertEquals(List.of("category", "radius"), textValues(schema.get("required")));
        assertFalse(schema.get("additionalProperties").asBoolean());
    }

    @Test
    void requiredContainsEveryComponentExceptNullableWrappers() {
        ObjectNode schema = JsonSchemaGenerator.schemaFor(AllTypes.class);

        assertEquals(List.of(
                        "text", "count", "l", "flag", "ratio",
                        "color", "tags", "names", "scores", "nested"),
                textValues(schema.get("required")));
        assertFalse(textValues(schema.get("required")).contains("boxed"));
        assertFalse(textValues(schema.get("required")).contains("boxedRatio"));
    }

    @Test
    void mapsScalarWrapperAndEnumTypes() {
        ObjectNode properties = JsonSchemaGenerator.schemaFor(AllTypes.class).withObject("/properties");

        assertEquals("string", properties.get("text").get("type").asText());
        assertEquals("integer", properties.get("count").get("type").asText());
        assertEquals("integer", properties.get("boxed").get("type").asText());
        assertEquals("integer", properties.get("l").get("type").asText());
        assertEquals("boolean", properties.get("flag").get("type").asText());
        assertEquals("number", properties.get("ratio").get("type").asText());
        assertEquals("number", properties.get("boxedRatio").get("type").asText());

        JsonNode color = properties.get("color");
        assertEquals("string", color.get("type").asText());
        assertEquals(List.of("RED", "GREEN"), textValues(color.get("enum")));
    }

    @Test
    void mapsArraysCollectionsAndMaps() {
        ObjectNode properties = JsonSchemaGenerator.schemaFor(AllTypes.class).withObject("/properties");

        JsonNode tags = properties.get("tags");
        assertEquals("array", tags.get("type").asText());
        assertEquals("string", tags.get("items").get("type").asText());

        JsonNode names = properties.get("names");
        assertEquals("array", names.get("type").asText());
        assertEquals("string", names.get("items").get("type").asText());

        JsonNode scores = properties.get("scores");
        assertEquals("object", scores.get("type").asText());
        assertEquals("integer", scores.get("additionalProperties").get("type").asText());

        JsonNode nested = properties.get("nested");
        assertEquals("object", nested.get("type").asText());
        assertEquals("string", nested.get("properties").get("name").get("type").asText());
        assertEquals(List.of("id", "name"), textValues(nested.get("required")));
    }

    @Test
    void descOnTheRecordTypeDocumentsTheWholeObject() {
        ObjectNode schema = JsonSchemaGenerator.schemaFor(Documented.class);
        assertEquals("整个对象", schema.get("description").asText());
        assertEquals("字段", schema.get("properties").get("value").get("description").asText());
    }

    @Test
    void blankDescIsNotEmittedAsAnEmptyDescription() {
        ObjectNode schema = JsonSchemaGenerator.schemaFor(BlankDescription.class);
        assertFalse(schema.get("properties").get("value").has("description"));
    }

    @Test
    void plainRecordWithoutAnyDescStillProducesATypeSchema() {
        ObjectNode schema = JsonSchemaGenerator.schemaFor(Bare.class);
        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.get("properties").get("value").get("type").asText());
        assertFalse(schema.get("properties").get("value").has("description"));
    }

    @Test
    void eachCallReturnsAFreshNode() {
        ObjectNode first = JsonSchemaGenerator.schemaFor(Bare.class);
        first.withObject("/properties").remove("value");
        first.put("type", "tampered");

        ObjectNode second = JsonSchemaGenerator.schemaFor(Bare.class);
        assertEquals("object", second.get("type").asText());
        assertNotNull(second.get("properties").get("value"));

        assertEquals(MAPPER.valueToTree(second), MAPPER.valueToTree(JsonSchemaGenerator.schemaFor(Bare.class)),
                "generation must be deterministic");
    }

    @Test
    void rejectsUnsupportedDeclarationsLoudly() {
        assertThrows(ToolDeclarationException.class, () -> JsonSchemaGenerator.schemaFor(String.class));
        assertThrows(ToolDeclarationException.class, () -> JsonSchemaGenerator.schemaFor(Unsupported.class));
        assertThrows(ToolDeclarationException.class, () -> JsonSchemaGenerator.schemaFor(RawCollection.class));
        assertThrows(ToolDeclarationException.class, () -> JsonSchemaGenerator.schemaFor(NonStringKeyMap.class));
        assertThrows(ToolDeclarationException.class, () -> JsonSchemaGenerator.schemaFor(Cyclic.class));
        assertThrows(NullPointerException.class, () -> JsonSchemaGenerator.schemaFor(null));
    }

    @Test
    void cyclicFailureMentionsTheType() {
        ToolDeclarationException failure = assertThrows(ToolDeclarationException.class,
                () -> JsonSchemaGenerator.schemaFor(Cyclic.class));
        assertTrue(failure.getMessage().contains("Cyclic"), failure.getMessage());
    }

    @Test
    void nestedDescriptionsAndOptionalNestedAreKept() {
        ObjectNode schema = JsonSchemaGenerator.schemaFor(HoldsNested.class);
        assertNull(schema.get("properties").get("nested").get("description"));
        assertEquals("string", schema.get("properties").get("nested").get("properties").get("name")
                .get("type").asText());
        assertEquals(List.of("id", "name"),
                textValues(schema.get("properties").get("nested").get("required")));
        assertEquals(List.of("nested"), textValues(schema.get("required")));
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.asText());
        }
        return values;
    }

    private enum Color { RED, GREEN }

    private record Nested(long id, String name) {
    }

    @Desc("整个对象")
    private record Documented(@Desc("字段") String value) {
    }

    private record BlankDescription(@Desc("   ") String value) {
    }

    private record Bare(String value) {
    }

    private record HoldsNested(Nested nested) {
    }

    private record AllTypes(
            String text,
            int count,
            Integer boxed,
            long l,
            boolean flag,
            double ratio,
            Double boxedRatio,
            Color color,
            List<String> tags,
            String[] names,
            Map<String, Integer> scores,
            Nested nested) {
    }

    private record Unsupported(Map.Entry<String, String> entry) {
    }

    private record RawCollection(List values) {
    }

    private record NonStringKeyMap(Map<Integer, String> values) {
    }

    private record Cyclic(Cyclic next) {
    }
}
