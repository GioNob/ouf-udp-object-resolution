package it.comune.trieste.ouf.udp;

/** The explicit Unicode White_Space set pinned by ouf.value-types.v1. */
final class ValueWhitespace {
  private ValueWhitespace() {}

  static boolean contains(int codePoint) {
    return codePoint >= 0x0009 && codePoint <= 0x000D
        || codePoint >= 0x2000 && codePoint <= 0x200A
        || switch (codePoint) {
          case 0x0020, 0x0085, 0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true;
          default -> false;
        };
  }

  static boolean isEmpty(String value) {
    return value.codePoints().allMatch(ValueWhitespace::contains);
  }

  static String trim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && contains(value.charAt(start))) start++;
    while (end > start && contains(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }
}
