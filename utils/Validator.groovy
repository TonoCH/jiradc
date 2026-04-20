package utils

class Validator {

    static boolean isBlank(String s) {
        return (s == null || s.length() == 0)
    }

    static boolean isNotBlank(String s) {
        return !isBlank(s)
    }

    static boolean isNull(Object obj) {
        return obj == null
    }

    static boolean isNotNull(Object obj) {
        return !isNull(obj)
    }

    static boolean isEmpty(Collection<Object> list) {
        return (list == null) || (list.size() == 0)
    }

    static boolean isNotEmpty(Collection<Object> list) {
        return !isEmpty(list)
    }

    static boolean contains(String s, String text) {
        if (isBlank(text)) return false
        return text.contains(s)
    }
}
