/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @library /test/lib
 * @build jdk.test.lib.Utils
 *        jdk.test.lib.compiler.CompilerUtils
 *        BasicTest
 * @run testng/othervm BasicTest
 */

import java.io.File;
import java.io.IOException;
import static java.lang.invoke.MethodHandles.Lookup.ClassOptions.*;
import static java.lang.invoke.MethodHandles.lookup;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Stream;

import jdk.test.lib.compiler.CompilerUtils;

import jdk.test.lib.Utils;

import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/* package-private */ interface HiddenTest {
  void test();
}

public class BasicTest {

    private static final Path SRC_DIR = Paths.get(Utils.TEST_SRC, "src");
    private static final Path CLASSES_DIR = Paths.get("classes");
    private static final Path CLASSES_10_DIR = Paths.get("classes_10");

    @BeforeTest
    static void setup() throws IOException {
        if (!CompilerUtils.compile(SRC_DIR, CLASSES_DIR, false, "-cp", Utils.TEST_CLASSES)) {
            throw new RuntimeException("Compilation of the test failed");
        }
    }

    static void compileSources(Path sourceFile, Path dest, String... options) throws IOException {
        Stream<String> ops = Stream.of("-cp", Utils.TEST_CLASSES + File.pathSeparator + CLASSES_DIR);
        if (options != null && options.length > 0) {
            ops = Stream.concat(ops, Arrays.stream(options));
        }
        if (!CompilerUtils.compile(sourceFile, dest, ops.toArray(String[]::new))) {
            throw new RuntimeException("Compilation of the test failed: " + sourceFile);
        }
    }

    static byte[] readClassFile(String classFileName) throws IOException {
        return Files.readAllBytes(CLASSES_DIR.resolve(classFileName));
    }

    static Class<HiddenTest> defineHiddenClass(String name) throws Exception {
        byte[] bytes = readClassFile(name + ".class");
        return (Class<HiddenTest>)lookup().defineHiddenClass(bytes, false, NESTMATE);
    }

    @Test
    public void hiddenClass() throws Throwable {
        HiddenTest t = defineHiddenClass("HiddenClass").newInstance();
        t.test();

        Class<?> c = t.getClass();
        Class<?>[] intfs = c.getInterfaces();
        assertTrue(intfs.length == 1);
        assertTrue(intfs[0] == HiddenTest.class);

        // test setAccessible
        checkSetAccessible(c, "realTest");
        checkSetAccessible(c, "test");
    }

    public void checkSetAccessible(Class<?> c, String name, Class<?>... ptypes) throws Exception {
        Method m = c.getDeclaredMethod(name, ptypes);
        assertFalse(m.trySetAccessible());
        try {
            m.setAccessible(true);
        } catch (InaccessibleObjectException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testLambda() throws Throwable {
        HiddenTest t = defineHiddenClass("Lambda").newInstance();
        try {
            t.test();
        } catch (Error e) {
            if (!e.getMessage().equals("thrown by " + t.getClass().getName())) {
                throw e;
            }
        }
    }

    @Test
    public void hiddenCantReflect() throws Throwable {
        HiddenTest t = defineHiddenClass("HiddenCantReflect").newInstance();
        t.test();

        Class<?> c = t.getClass();
        Class<?>[] intfs = c.getInterfaces();
        assertTrue(intfs.length == 1);
        assertTrue(intfs[0] == HiddenTest.class);

        try {
            // this will cause class loading of HiddenCantReflect and fail
            c.getDeclaredMethods();
        } catch (NoClassDefFoundError e) {
            Throwable x = e.getCause();
            if (x == null || !(x instanceof ClassNotFoundException && x.getMessage().contains("HiddenCantReflect"))) {
                throw e;
            }
        }
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void hiddenInterface() throws Exception {
        byte[] bytes = readClassFile("HiddenInterface.class");
        Class<?> c = lookup().defineHiddenClass(bytes, false);
    }


    @Test(expectedExceptions = IllegalArgumentException.class)
    public void abstractHiddenClass() throws Exception {
        byte[] bytes = readClassFile("AbstractClass.class");
        Class<?> c = lookup().defineHiddenClass(bytes, false);
    }

    @Test(expectedExceptions = NoClassDefFoundError.class)
    public void hiddenSuperClass() throws Exception {
        byte[] bytes = readClassFile("HiddenSuper.class");
        Class<?> c = lookup().defineHiddenClass(bytes, false);
    }

    @Test
    public void hiddenNestmates() throws Throwable {
        try {
            byte[] bytes = readClassFile("Outer.class");
            lookup().defineHiddenClass(bytes, false);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("NestMembers attribute")) throw e;
        }

        try {
            byte[] bytes = readClassFile("Outer$Inner.class");
            lookup().defineHiddenClass(bytes, false);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("NestHost attribute")) throw e;
        }
    }

    @Test
    public void hiddenNestedClass() throws Throwable {
        // compile with --release 10 with no NestHost and NestMembers attribute
        compileSources(SRC_DIR.resolve("Outer.java"), CLASSES_10_DIR, "--release", "10");
        try {
            byte[] bytes = Files.readAllBytes(CLASSES_10_DIR.resolve("Outer.class"));
            lookup().defineHiddenClass(bytes, false);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("Outer$Inner")) throw e;
        }

        try {
            byte[] bytes = Files.readAllBytes(CLASSES_10_DIR.resolve("Outer$Inner.class"));
            lookup().defineHiddenClass(bytes, false);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("Outer$Inner")) throw e;
        }
    }

    @Test
    public void hiddenAnonymous() throws Throwable {
        // compile with --release 10 with no NestHost and NestMembers attribute
        compileSources(SRC_DIR.resolve("EnclosingClass.java"), CLASSES_10_DIR, "--release", "10");
        try {
            byte[] bytes = Files.readAllBytes(CLASSES_10_DIR.resolve("EnclosingClass.class"));
            lookup().defineHiddenClass(bytes, false);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("EnclosingClass$1")) throw e;
        }

        try {
            byte[] bytes = Files.readAllBytes(CLASSES_10_DIR.resolve("EnclosingClass$1.class"));
            lookup().defineHiddenClass(bytes, false);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("EnclosingMethod attribute")) throw e;
        }
    }
}
