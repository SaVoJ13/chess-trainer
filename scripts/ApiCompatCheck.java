// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Статична перевірка сумісності байткоду з minSdk (CONTRACTS 7.2, «Сумісність байткоду з API 26»).
 *
 * Шукає посилання на класи, методи й поля платформи Android, що з'явилися пізніше за minSdk
 * (атрибут `since` у `platforms/android-NN/data/api-versions.xml`). Перевіряються лише посилання з класів
 * «області» (за замовчуванням код проєкту, chesslib і commons-lang3): решта залежностей (AndroidX, Compose,
 * Kotlin) сама захищає виклики перевіркою Build.VERSION і дала б хибні спрацьовування.
 *
 * Запуск (JDK 17+, без залежностей):
 *   java scripts/ApiCompatCheck.java --apk app/build/outputs/apk/debug/app-debug.apk
 *   java scripts/ApiCompatCheck.java --classes core-chess/api/build/classes/kotlin/main
 *   java scripts/ApiCompatCheck.java --self-test
 *
 * Код виходу: 0 порушень немає; 1 є порушення; 2 помилка запуску/інструментів.
 * Поведінкові відмінності версій API скрипт не виявляє, лише наявність символів.
 */
public final class ApiCompatCheck {

    /** Класи, посилання з яких перевіряються (внутрішні імена через `/`). */
    private static final List<String> DEFAULT_SCOPE = List.of(
            "org/chesstrainer/", "com/github/bhlangonijr/", "org/apache/commons/lang3/");

    private record AllowedException(String callerPrefix, String symbol, String reason) {}

    /**
     * Дозволені винятки (CONTRACTS 7.2): «клас-джерело (префікс), символ платформи, причина».
     * Кожен виняток має бути виправданий; новий додається лише після перевірки досяжності й походження.
     */
    private static final List<AllowedException> ALLOWED = List.of(
            new AllowedException("org/apache/commons/lang3/reflect/TypeUtils",
                    "java/lang/reflect/Type.getTypeName()Ljava/lang/String;",
                    "недосяжний із застосунку: з chesslib викликаються лише StringUtils.isEmpty/isBlank/isNotEmpty/isNotBlank"
                            + " (PROTOTYPE.md розд. 6)"),
            new AllowedException("org/apache/commons/lang3/text/StrBuilder",
                    "java/lang/StringBuilder.getChars(II[CI)V",
                    "метод успадковано від прихованого AbstractStringBuilder (є з API 1); api-versions.xml 37 знаходить"
                            + " лише CharSequence.getChars (default, API 37), бо AbstractStringBuilder у файлі відсутній"),
            new AllowedException("org/apache/commons/lang3/text/StrBuilder",
                    "java/lang/StringBuffer.getChars(II[CI)V",
                    "те саме, що для StringBuilder.getChars"));

    private record Reference(String caller, String kind, String owner, String member) {
        String describe() {
            return kind + " " + owner + (member.isEmpty() ? "" : "." + member);
        }
    }

    private record Violation(Reference ref, int since) {}

    private static final class ApiClass {
        int since = 1;
        String superClass;
        final List<String> interfaces = new ArrayList<>();
        final Map<String, Integer> methods = new HashMap<>();
        final Map<String, Integer> fields = new HashMap<>();
    }

    private static final class Api extends DefaultHandler {
        final Map<String, ApiClass> classes = new HashMap<>();
        private ApiClass current;

        @Override
        public void startElement(String uri, String local, String qName, Attributes a) {
            switch (qName) {
                case "class" -> {
                    current = new ApiClass();
                    String since = a.getValue("since");
                    if (since != null) current.since = parseSince(since);
                    classes.put(a.getValue("name"), current);
                }
                case "extends" -> {
                    if (current != null) current.superClass = a.getValue("name");
                }
                case "implements" -> {
                    if (current != null) current.interfaces.add(a.getValue("name"));
                }
                case "method" -> {
                    if (current != null) current.methods.put(a.getValue("name"), memberSince(a));
                }
                case "field" -> {
                    if (current != null) current.fields.put(a.getValue("name"), memberSince(a));
                }
                default -> { }
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            if (qName.equals("class")) current = null;
        }

        private int memberSince(Attributes a) {
            String since = a.getValue("since");
            return since != null ? parseSince(since) : current.since;
        }

        private static int parseSince(String s) {
            return (int) Double.parseDouble(s); // "36.1" (мінорні випуски) → 36
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) usage("unexpected argument " + args[i]);
            String key = args[i].substring(2);
            if (key.equals("self-test") || key.equals("strict")) {
                opts.put(key, "true");
            } else {
                if (i + 1 >= args.length) usage("missing value for " + args[i]);
                opts.put(key, args[++i]);
            }
        }
        int minSdk = Integer.parseInt(opts.getOrDefault("min-sdk", "26"));
        List<String> scope = opts.containsKey("scope") ? List.of(opts.get("scope").split(",")) : DEFAULT_SCOPE;
        boolean strict = opts.containsKey("strict");

        if (opts.containsKey("self-test")) {
            System.exit(selfTest(opts, minSdk, scope));
        }
        if (opts.containsKey("apk") == opts.containsKey("classes")) usage("exactly one of --apk or --classes is required");

        Api api = loadApi(findApiVersions(opts));
        List<Reference> refs = opts.containsKey("apk")
                ? scanApk(Paths.get(opts.get("apk")), findDexdump(opts), scope)
                : scanClasses(Paths.get(opts.get("classes")), scope);
        System.exit(report(api, refs, minSdk, strict));
    }

    // --- звіт ---

    private static int report(Api api, List<Reference> refs, int minSdk, boolean strict) {
        Map<Violation, Integer> violations = new TreeMap<>(Comparator
                .comparing((Violation v) -> v.ref().caller())
                .thenComparing(v -> v.ref().describe()));
        Map<String, Integer> unresolved = new TreeMap<>();
        int platformRefs = 0;
        int allowed = 0;
        for (Reference ref : refs) {
            ApiClass owner = api.classes.get(ref.owner());
            if (owner == null) continue; // не платформа
            platformRefs++;
            Integer since = resolve(api, ref);
            if (since == null) {
                unresolved.merge(ref.describe(), 1, Integer::sum);
                continue;
            }
            if (since <= minSdk) continue;
            if (isAllowed(ref)) {
                allowed++;
                continue;
            }
            violations.merge(new Violation(ref, since), 1, Integer::sum);
        }
        System.out.printf("min-sdk %d: %d references checked, %d to the platform, %d allowed exceptions, "
                        + "%d unresolved, %d violations%n",
                minSdk, refs.size(), platformRefs, allowed, unresolved.size(), violations.size());
        violations.forEach((v, n) -> System.out.printf("VIOLATION API %d: %s  (from %s, %d time(s))%n",
                v.since(), v.ref().describe(), v.ref().caller().replace('/', '.'), n));
        unresolved.forEach((d, n) -> System.out.printf("%s: not in api-versions.xml: %s (%d)%n",
                strict ? "UNRESOLVED" : "note", d, n));
        if (!violations.isEmpty() || (strict && !unresolved.isEmpty())) {
            System.out.println("FAILED");
            return 1;
        }
        System.out.println("OK");
        return 0;
    }

    private static boolean isAllowed(Reference ref) {
        String member = ref.owner() + "." + ref.member();
        return ALLOWED.stream()
                .anyMatch(e -> ref.caller().startsWith(e.callerPrefix()) && e.symbol().equals(member));
    }

    /** `since` символу: сам клас, далі предки (методи й поля успадковуються). `null`, якщо символ невідомий. */
    private static Integer resolve(Api api, Reference ref) {
        ApiClass owner = api.classes.get(ref.owner());
        if (ref.kind().equals("class")) return owner.since;
        int classSince = owner.since;
        Integer member = findMember(api, ref.owner(), ref.member(), ref.kind().equals("method"), new HashMap<>());
        if (member == null && ref.kind().equals("method")) {
            member = findMember(api, "java/lang/Object", ref.member(), true, new HashMap<>());
        }
        return member == null ? null : Math.max(member, classSince);
    }

    /**
     * Порядок пошуку як у JVM: клас, ланцюг суперкласів, потім інтерфейси. Якщо суперклас прихований (його немає
     * в api-versions.xml, як AbstractStringBuilder), він міг оголосити метод раніше за інтерфейс, тож за інтерфейсами
     * вже не шукаємо: символ лишається невизначеним (примітка, не порушення).
     */
    private static Integer findMember(Api api, String cls, String member, boolean method, Map<String, Boolean> seen) {
        if (seen.put(cls, true) != null) return null;
        ApiClass c = api.classes.get(cls);
        if (c == null) return null;
        Integer own = (method ? c.methods : c.fields).get(member);
        if (own != null) return own;
        if (c.superClass != null) {
            if (!api.classes.containsKey(c.superClass)) return null;
            Integer inherited = findMember(api, c.superClass, member, method, seen);
            if (inherited != null) return inherited;
        }
        for (String iface : c.interfaces) {
            Integer inherited = findMember(api, iface, member, method, seen);
            if (inherited != null) return inherited;
        }
        return null;
    }

    // --- api-versions.xml і інструменти ---

    private static Api loadApi(Path xml) throws Exception {
        Api api = new Api();
        try (InputStream in = Files.newInputStream(xml)) {
            SAXParserFactory.newInstance().newSAXParser().parse(in, api);
        }
        return api;
    }

    private static Path findApiVersions(Map<String, String> opts) {
        if (opts.containsKey("api-versions")) return Paths.get(opts.get("api-versions"));
        Path sdk = sdkRoot(opts);
        Path platforms = sdk.resolve("platforms");
        if (Files.isDirectory(platforms)) {
            try (Stream<Path> dirs = Files.list(platforms)) {
                return dirs.filter(d -> Files.isRegularFile(d.resolve("data/api-versions.xml")))
                        .max(Comparator.comparing(ApiCompatCheck::platformLevel))
                        .map(d -> d.resolve("data/api-versions.xml"))
                        .orElseThrow(() -> fail("no platforms/android-*/data/api-versions.xml in " + sdk));
            } catch (IOException e) {
                throw fail(e.toString());
            }
        }
        throw fail("api-versions.xml not found: pass --api-versions or set ANDROID_HOME");
    }

    private static double platformLevel(Path dir) {
        Matcher m = Pattern.compile("android-(\\d+(?:\\.\\d+)?)").matcher(dir.getFileName().toString());
        return m.matches() ? Double.parseDouble(m.group(1)) : 0;
    }

    private static Path sdkRoot(Map<String, String> opts) {
        String root = opts.getOrDefault("sdk", firstNonNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")));
        if (root == null) throw fail("set ANDROID_HOME or pass --sdk");
        return Paths.get(root);
    }

    private static String findDexdump(Map<String, String> opts) {
        if (opts.containsKey("dexdump")) return opts.get("dexdump");
        Path tools = sdkRoot(opts).resolve("build-tools");
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "dexdump.exe" : "dexdump";
        try (Stream<Path> dirs = Files.list(tools)) {
            return dirs.filter(d -> Files.isRegularFile(d.resolve(exe)))
                    .max(Comparator.comparing(d -> d.getFileName().toString(), ApiCompatCheck::compareVersions))
                    .map(d -> d.resolve(exe).toString())
                    .orElseThrow(() -> fail("dexdump not found in " + tools));
        } catch (IOException e) {
            throw fail(e.toString());
        }
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? safeInt(x[i]) : 0;
            int yi = i < y.length ? safeInt(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --- сканування APK (dexdump) ---

    private static final Pattern DEX_CLASS = Pattern.compile("^\\s*Class descriptor\\s*:\\s*'L([^;]+);'");
    private static final Pattern DEX_METHOD =
            Pattern.compile("\\bL([^;]+);\\.([^:\\s]+):(\\([^)]*\\)\\S+) // method@");
    private static final Pattern DEX_FIELD = Pattern.compile("\\bL([^;]+);\\.([^:\\s]+):\\S+ // field@");
    private static final Pattern DEX_TYPE = Pattern.compile(", L([^;]+); // type@");

    private static List<Reference> scanApk(Path apk, String dexdump, List<String> scope) throws IOException {
        Path tmp = Files.createTempDirectory("apicompat");
        List<Reference> refs = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            List<Path> dex = new ArrayList<>();
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry e = entries.nextElement();
                if (e.getName().matches("classes\\d*\\.dex")) {
                    Path target = tmp.resolve(e.getName());
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, target);
                    }
                    dex.add(target);
                }
            }
            if (dex.isEmpty()) throw fail("no classes*.dex in " + apk);
            // dexdump для Windows не відкриває APK напряму (mmap), тому працюємо з витягнутими .dex.
            for (Path file : dex) {
                scanDexdump(run(List.of(dexdump, "-d", file.toString())), scope, refs);
            }
        } finally {
            deleteTree(tmp);
        }
        return refs;
    }

    private static void scanDexdump(BufferedReader out, List<String> scope, List<Reference> refs) throws IOException {
        String caller = null;
        boolean inScope = false;
        String line;
        while ((line = out.readLine()) != null) {
            Matcher cm = DEX_CLASS.matcher(line);
            if (cm.find()) {
                caller = cm.group(1);
                inScope = inScope(caller, scope);
                continue;
            }
            if (!inScope || !line.contains("//")) continue;
            Matcher m;
            if (line.contains("// method@") && (m = DEX_METHOD.matcher(line)).find()) {
                refs.add(new Reference(caller, "method", m.group(1), m.group(2).replace("\"", "") + m.group(3)));
            } else if (line.contains("// field@") && (m = DEX_FIELD.matcher(line)).find()) {
                refs.add(new Reference(caller, "field", m.group(1), m.group(2)));
            } else if (line.contains("// type@") && (m = DEX_TYPE.matcher(line)).find()) {
                refs.add(new Reference(caller, "class", m.group(1), ""));
            }
        }
    }

    // --- сканування .class (javap) ---

    private static final Pattern JAVAP_TYPE_DECL =
            Pattern.compile("^(?:[a-z]+ )*(?:class|interface|enum|record) ([\\w.$]+)");
    private static final Pattern JAVAP_METHOD =
            Pattern.compile("// (?:Interface)?Method \"?([^.\"]+)\"?\\.([^:]+):(\\(.*)$");
    private static final Pattern JAVAP_FIELD = Pattern.compile("// Field ([^.]+)\\.([^:]+):");
    private static final Pattern JAVAP_CLASS = Pattern.compile("// class \"?([^\"\\s]+)\"?$");

    private static List<Reference> scanClasses(Path dir, List<String> scope) throws IOException {
        if (!Files.isDirectory(dir)) throw fail("not a directory: " + dir);
        List<String> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).map(Path::toString).sorted().toList();
        }
        if (files.isEmpty()) throw fail("no .class files in " + dir);
        List<Reference> refs = new ArrayList<>();
        // javap не читає файли аргументів, тому запускаємо його порціями, щоб не перевищити довжину командного рядка.
        List<String> batch = new ArrayList<>();
        int length = 0;
        for (String file : files) {
            if (!batch.isEmpty() && length + file.length() > 20_000) {
                scanJavap(batch, scope, refs);
                batch.clear();
                length = 0;
            }
            batch.add(file);
            length += file.length() + 1;
        }
        if (!batch.isEmpty()) scanJavap(batch, scope, refs);
        return refs;
    }

    private static void scanJavap(List<String> files, List<String> scope, List<Reference> refs) throws IOException {
        List<String> command = new ArrayList<>(List.of(javapExecutable(), "-c", "-p", "-constants"));
        command.addAll(files);
        try (BufferedReader out = run(command)) {
            String caller = null;
            boolean inScope = false;
            String line;
            while ((line = out.readLine()) != null) {
                if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && line.endsWith("{")) {
                    Matcher tm = JAVAP_TYPE_DECL.matcher(line);
                    if (tm.find()) {
                        caller = tm.group(1).replace('.', '/');
                        inScope = inScope(caller, scope);
                    }
                    continue;
                }
                if (!inScope || !line.contains("//")) continue;
                Matcher m;
                if ((m = JAVAP_METHOD.matcher(line)).find()) {
                    if (!m.group(1).startsWith("[")) {
                        refs.add(new Reference(caller, "method", m.group(1), m.group(2).replace("\"", "") + m.group(3)));
                    }
                } else if ((m = JAVAP_FIELD.matcher(line)).find()) {
                    refs.add(new Reference(caller, "field", m.group(1), m.group(2)));
                } else if ((m = JAVAP_CLASS.matcher(line)).find() && !m.group(1).startsWith("[")) {
                    refs.add(new Reference(caller, "class", m.group(1), ""));
                }
            }
        }
    }

    private static String javapExecutable() {
        String home = System.getProperty("java.home");
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "javap.exe" : "javap";
        return Paths.get(home, "bin", exe).toString();
    }

    // --- самоперевірка: інструмент має ловити порушення, а не завжди повертати успіх ---

    private static int selfTest(Map<String, String> opts, int minSdk, List<String> scope) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw fail("self-test needs a JDK (javax.tools.JavaCompiler)");
        Path tmp = Files.createTempDirectory("apicompat-selftest");
        try {
            Path bad = write(tmp.resolve("src/synthetic/Bad.java"), """
                    package synthetic;
                    public class Bad {
                        // Type.getTypeName() з'явився в API 28, String.isBlank() у API 33.
                        public String a(java.lang.reflect.Type t) { return t.getTypeName(); }
                        public boolean b(String s) { return s.isBlank(); }
                    }
                    """);
            Path good = write(tmp.resolve("src/synthetic/Good.java"), """
                    package synthetic;
                    public class Good {
                        public int a(String s) { return s.length() + new java.util.ArrayList<String>().size(); }
                    }
                    """);
            Path out = Files.createDirectories(tmp.resolve("out"));
            int rc = compiler.run(null, null, null, "-d", out.toString(), "--release", "17", bad.toString(), good.toString());
            if (rc != 0) throw fail("self-test: compilation failed");

            Api api = loadApi(findApiVersions(opts));
            List<Reference> refs = scanClasses(out, List.of("synthetic/"));
            List<Reference> onlyGood = refs.stream().filter(r -> r.caller().equals("synthetic/Good")).toList();
            List<Reference> onlyBad = refs.stream().filter(r -> r.caller().equals("synthetic/Bad")).toList();

            System.out.println("--- must fail (synthetic/Bad) ---");
            boolean badFails = report(api, onlyBad, minSdk, false) == 1;
            System.out.println("--- must pass (synthetic/Good) ---");
            boolean goodPasses = report(api, onlyGood, minSdk, false) == 0;
            System.out.println("--- the same classes must pass when minSdk is high enough (36) ---");
            boolean higherSdkPasses = report(api, onlyBad, 36, false) == 0;
            System.out.println("--- allowed exception must not hide other callers ---");
            List<Reference> exempt = List.of(
                    new Reference("org/apache/commons/lang3/reflect/TypeUtils", "method", "java/lang/reflect/Type",
                            "getTypeName()Ljava/lang/String;"));
            boolean exceptionAccepted = report(api, exempt, minSdk, false) == 0;
            List<Reference> notExempt = List.of(
                    new Reference("org/chesstrainer/app/Foo", "method", "java/lang/reflect/Type",
                            "getTypeName()Ljava/lang/String;"));
            boolean exceptionScoped = report(api, notExempt, minSdk, false) == 1;

            boolean ok = badFails && goodPasses && higherSdkPasses && exceptionAccepted && exceptionScoped;
            System.out.println(ok ? "SELF-TEST OK" : "SELF-TEST FAILED");
            return ok ? 0 : 1;
        } finally {
            deleteTree(tmp);
        }
    }

    private static Path write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return path;
    }

    // --- службове ---

    private static boolean inScope(String cls, List<String> scope) {
        for (String prefix : scope) if (cls.startsWith(prefix)) return true;
        return false;
    }

    private static BufferedReader run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        // Вивід складаємо в тимчасовий файл: dexdump дає сотні МБ тексту, а нечитаний канал блокує процес.
        Path dump = Files.createTempFile("apicompat", ".txt");
        try (InputStream in = process.getInputStream()) {
            Files.copy(in, dump, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            if (process.waitFor() != 0) {
                try (Stream<String> head = Files.lines(dump, StandardCharsets.UTF_8)) {
                    head.limit(5).forEach(System.err::println);
                }
                throw fail(command.get(0) + " exited with " + process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail("interrupted");
        }
        return new BufferedReader(new InputStreamReader(new TempFileStream(dump), StandardCharsets.UTF_8));
    }

    /** Читає тимчасовий файл і видаляє його при закритті. */
    private static final class TempFileStream extends InputStream {
        private final Path path;
        private final InputStream in;

        TempFileStream(Path path) throws IOException {
            this.path = path;
            this.in = Files.newInputStream(path);
        }

        @Override public int read() throws IOException { return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return in.read(b, off, len); }

        @Override
        public void close() throws IOException {
            in.close();
            Files.deleteIfExists(path);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // тимчасові файли, не критично
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static IllegalStateException fail(String message) {
        System.err.println("error: " + message);
        System.exit(2);
        return new IllegalStateException(message);
    }

    private static void usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("""
                usage: java scripts/ApiCompatCheck.java (--apk FILE | --classes DIR | --self-test)
                         [--min-sdk 26] [--scope pkg/prefix/,other/] [--api-versions FILE] [--sdk DIR]
                         [--dexdump PATH] [--strict]""");
        System.exit(2);
    }
}
