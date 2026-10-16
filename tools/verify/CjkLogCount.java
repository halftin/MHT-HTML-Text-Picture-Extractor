import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Independent UTF-8 code-point counter (same documented Han ranges as PS probe). */
public final class CjkLogCount {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("en") || args[0].equals("zh")))
            throw new IllegalArgumentException("Usage: CjkLogCount.java en|zh log [log ...]");
        boolean passed = true;
        for (int i = 1; i < args.length; i++) {
            Path file = Path.of(args[i]);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isEmpty()) throw new AssertionError("Empty log: " + file);
            long count = content.codePoints().filter(c -> c >= 0x3400 && c <= 0x4dbf
                    || c >= 0x4e00 && c <= 0x9fff || c >= 0xf900 && c <= 0xfaff
                    || c >= 0x20000 && c <= 0x2ffff || c >= 0x30000 && c <= 0x323af).count();
            boolean ok = args[0].equals("en") ? count == 0 : count > 0;
            System.out.println("INDEPENDENT_COUNT language=" + args[0] + " file=" + file
                    + " CJK=" + count + " pass=" + ok);
            passed &= ok;
        }
        System.exit(passed ? 0 : 1);
    }
}
