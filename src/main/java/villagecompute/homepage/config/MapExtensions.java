package villagecompute.homepage.config;

import io.quarkus.qute.TemplateExtension;

import java.util.Map;

/**
 * Qute template extensions for safely accessing Map properties.
 *
 * <p>
 * Qute cannot infer types for Map<String, Object> fields, so these extensions provide type-safe access methods that can
 * be used in templates without build-time validation errors.
 * </p>
 */
@TemplateExtension
public class MapExtensions {

    /**
     * Safely gets a String value from a Map<String, Object>, returning null if not found or not a String.
     *
     * @param map
     *            the map to access
     * @param key
     *            the key to look up
     * @return the value as String, or null
     */
    public static String getString(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof String ? (String) value : null;
    }

    /**
     * Safely gets a nested Map from a Map<String, Object>.
     *
     * @param map
     *            the map to access
     * @param key
     *            the key to look up
     * @return the nested map, or null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /**
     * Safely gets a value from a Map<String, Object>.
     *
     * @param map
     *            the map to access
     * @param key
     *            the key to look up
     * @return the value, or null
     */
    public static Object get(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    /**
     * Checks if a Map contains a key.
     *
     * @param map
     *            the map to check
     * @param key
     *            the key to look for
     * @return true if key exists
     */
    public static boolean has(Map<String, Object> map, String key) {
        return map != null && map.containsKey(key);
    }

    /**
     * Safely gets a List from a Map<String, Object>.
     *
     * @param map
     *            the map to access
     * @param key
     *            the key to look up
     * @return the list, or null
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof java.util.List ? (java.util.List<Map<String, Object>>) value : null;
    }
}
