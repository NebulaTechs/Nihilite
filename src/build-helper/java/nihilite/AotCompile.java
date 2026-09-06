package nihilite.build;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AotCompile {
    private AotCompile() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: AotCompile <source-root> <compile-path> <ns-sym>...");
            System.exit(2);
        }
        String sourceRoot = args[0];
        String compilePath = args[1];
        new File(compilePath).mkdirs();

        Var compileFiles = RT.var("clojure.core", "*compile-files*");
        Var compilePathVar = RT.var("clojure.core", "*compile-path*");

        Var.pushThreadBindings(RT.map(
                compileFiles, Boolean.TRUE,
                compilePathVar, compilePath));

        try {
            List<String> nsNames = new ArrayList<>();
            for (int i = 2; i < args.length; i++) nsNames.add(args[i]);
            Collections.sort(nsNames);

            Var compileFn = RT.var("clojure.core", "compile");
            for (String nsName : nsNames) {
                Path clj = nsToCljPath(sourceRoot, nsName);
                if (!clj.toFile().isFile()) {
                    System.err.println("skip (no source): " + nsName + " (looked for " + clj + ")");
                    continue;
                }
                ensurePackageDirs(compilePath, nsName);
                System.out.println("aot: " + nsName);
                compileFn.invoke(Symbol.intern(nsName));
            }
        } finally {
            Var.popThreadBindings();
        }
    }

    private static Path nsToCljPath(String sourceRoot, String nsName) {
        return Paths.get(sourceRoot, nsName.replace('.', File.separatorChar) + ".clj");
    }

    private static void ensurePackageDirs(String compilePath, String nsName) {
        String[] parts = nsName.split("\\.");
        File dir = new File(compilePath);
        for (int i = 0; i < parts.length - 1; i++) {
            dir = new File(dir, parts[i]);
            dir.mkdir();
        }
    }
}
