package demo;

import java.lang.classfile.*;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

/**
 * Demonstrates the final Class-File API (JDK 24+); no preview flags or libraries.
 * Compile: javac --release 24 ClassFileApiDemo.java
 * Run:     java ClassFileApiDemo [directory-for-generated-class-files]
 * Writes GeneratedExample.java in the working directory, independently of that argument.
 */
public class ClassFileApiDemo {
    private static final ClassFile CF = ClassFile.of();
    private static final ClassDesc DEMO = ClassDesc.of("GeneratedExample");
    private static final MethodTypeDesc GREETING = MethodTypeDesc.of(CD_String);
    private static final MethodTypeDesc INT_TO_INT = MethodTypeDesc.of(CD_int, CD_int);

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "generated" : args[0]);
        Files.createDirectories(output);

        // SourceFileAttribute only records a name; write the source separately.
        // This illustrative source matches the original class, before transformation.
        Path source = Path.of("GeneratedExample.java").toAbsolutePath();
        Files.writeString(source, """
                public class GeneratedExample {
                    private static final int UNUSED = 123;

                    public GeneratedExample() {
                        super();
                    }

                    public static String greeting() {
                        return "Hello";
                    }

                    public static int absolute(int value) {
                        if (value < 0) {
                            return -value;
                        }
                        return value;
                    }
                }
                """);
        System.out.println("Java source saved: " + source);

        // 1. Generate a complete class directly, without compiling Java source.
        byte[] original = generate();
        verify("original", original);
        Files.write(output.resolve("GeneratedExample.class"), original);

        // 2. Parse an immutable, lazily populated model; inspect without loading.
        ClassModel model = CF.parse(original);
        inspect(model);

        // 3. Transform code, remove a field, and add a method in one pipeline.
        byte[] changed = transform(model);
        verify("transformed", changed);
        Path changedDir = Files.createDirectories(output.resolve("transformed"));
        Files.write(changedDir.resolve("GeneratedExample.class"), changed);

        // 4. Loading is a separate JVM operation, not part of the Class-File API.
        // Separate loaders allow both versions to have the same binary name.
        Class<?> before = new ByteArrayLoader().define(original);
        Class<?> after = new ByteArrayLoader().define(changed);
        Object oldGreeting = before.getMethod("greeting").invoke(null);
        Object newGreeting = after.getMethod("greeting").invoke(null);
        System.out.println("Before: " + oldGreeting);
        System.out.println("After:  " + newGreeting);
        System.out.println("Added method: " + after.getMethod("answer").invoke(null));

        // Exercise both branches of generated code and check the transformation.
        check("Hello".equals(oldGreeting), "original greeting");
        check("Hello from transformed bytecode".equals(newGreeting), "new greeting");
        check(after.getMethod("answer").invoke(null).equals(42), "added method");
        check(before.getDeclaredFields().length == 1, "original field");
        check(after.getDeclaredFields().length == 0, "field removed");
        for (Class<?> type : new Class<?>[] {before, after}) {
            type.getConstructor().newInstance();
            for (int input : new int[] {-7, 0, 7}) {
                int result = (int) type.getMethod("absolute", int.class).invoke(null, input);
                check(result == Math.abs(input), "absolute(" + input + ")");
            }
        }
        System.out.println("All checks passed. Class files: " + output.toAbsolutePath());
    }

    private static byte[] generate() {
        // ClassDesc and MethodTypeDesc express symbolic names and signatures.
        return CF.build(DEMO, cb -> {
            cb.withVersion(JAVA_24_VERSION, 0);
            cb.withFlags(ACC_PUBLIC | ACC_SUPER);
            cb.withSuperclass(CD_Object);
            cb.with(SourceFileAttribute.of("GeneratedExample.java"));

            // Standard attributes and constant-pool entries can be built directly.
            cb.withField("UNUSED", CD_int, fb -> fb
                    .withFlags(ACC_PRIVATE | ACC_STATIC | ACC_FINAL)
                    .with(ConstantValueAttribute.of(cb.constantPool().intEntry(123))));

            cb.withMethodBody(INIT_NAME, MethodTypeDesc.of(CD_void), ACC_PUBLIC,
                    code -> code.aload(0)
                            .invokespecial(CD_Object, INIT_NAME, MethodTypeDesc.of(CD_void))
                            .return_());

            cb.withMethodBody("greeting", GREETING, ACC_PUBLIC | ACC_STATIC,
                    code -> code.ldc("Hello").areturn());

            cb.withMethodBody("absolute", INT_TO_INT, ACC_PUBLIC | ACC_STATIC, code -> {
                Label nonNegative = code.newLabel();
                code.iload(0).ifge(nonNegative);
                code.iload(0).ineg().ireturn();
                code.labelBinding(nonNegative);
                code.iload(0).ireturn();
                // The API computes max stack/locals and required stack-map frames.
                // Like Math.abs(int), MIN_VALUE remains negative on overflow.
            });
        });
    }

    private static void inspect(ClassModel model) {
        System.out.printf("Class: %s, version: %d.%d, flags: %s%n",
                model.thisClass().asInternalName(), model.majorVersion(),
                model.minorVersion(), model.flags().flags());
        System.out.println("Constant-pool slots (including reserved slot 0): "
                + model.constantPool().size());
        model.findAttribute(Attributes.sourceFile()).ifPresent(a ->
                System.out.println("SourceFile: " + a.sourceFile().stringValue()));
        for (FieldModel field : model.fields()) {
            System.out.printf("Field: %s %s%n", field.fieldName().stringValue(),
                    field.fieldType().stringValue());
        }
        for (MethodModel method : model.methods()) {
            System.out.printf("Method: %s%s%n", method.methodName().stringValue(),
                    method.methodType().stringValue());
            method.code().ifPresent(code -> {
                // Code also contains labels and other pseudo-instructions.
                for (CodeElement element : code) {
                    if (element instanceof Instruction instruction) {
                        System.out.println("  " + instruction.opcode());
                    }
                }
            });
        }
    }

    private static byte[] transform(ClassModel model) {
        CodeTransform replaceHello = (builder, element) -> {
            if (element instanceof ConstantInstruction constant
                    && "Hello".equals(constant.constantValue())) {
                builder.ldc("Hello from transformed bytecode");
            } else {
                builder.with(element); // Forward everything else unchanged.
            }
        };

        CodeTransform traceEntry = new CodeTransform() {
            @Override
            public void atStart(CodeBuilder builder) {
                builder.getstatic(ClassDesc.of("java.lang.System"), "out",
                                ClassDesc.of("java.io.PrintStream"))
                        .ldc("[trace] entering greeting")
                        .invokevirtual(ClassDesc.of("java.io.PrintStream"), "println",
                                MethodTypeDesc.of(CD_void, CD_String));
            }

            @Override
            public void accept(CodeBuilder builder, CodeElement element) {
                builder.with(element);
            }
        };

        ClassTransform pipeline = ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("greeting")
                        && method.methodTypeSymbol().equals(GREETING),
                replaceHello.andThen(traceEntry))
                .andThen(ClassTransform.dropping(element ->
                        element instanceof FieldModel field
                                && field.fieldName().equalsString("UNUSED")))
                .andThen(ClassTransform.endHandler(cb -> cb.withMethodBody(
                        "answer", MethodTypeDesc.of(CD_int), ACC_PUBLIC | ACC_STATIC,
                        code -> code.bipush(42).ireturn())));

        // Rebuild the pool rather than retaining unused original constants.
        return CF.withOptions(ConstantPoolSharingOption.NEW_POOL)
                .transformClass(model, pipeline);
    }

    private static void verify(String label, byte[] bytes) {
        var errors = CF.verify(bytes);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(label + " verification failed: " + errors);
        }
        System.out.println("Verified: " + label);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static final class ByteArrayLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
