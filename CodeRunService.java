package com.trainingmug.practiceplatform.user.util.coderunner;

import jep.SharedInterpreter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.InputMismatchException;
import java.util.NoSuchElementException;

@Service
@Slf4j
public class CodeRunService {
    public static String compileJavaCode(String code) throws Exception {
        String className = "Solution";
        String fileName = className + ".java";

        // Write Java code to a file
        File sourceFile = new File(fileName);
        try (FileWriter writer = new FileWriter(sourceFile)) {
            writer.write(code);
        }

        // Prepare a stream to capture compilation errors
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, new PrintStream(errorStream), sourceFile.getPath());

        if (result != 0) {
            return errorStream.toString();
        }
        return null;
    }

    // Method to execute compiled Java class with input
    public static String runJavaClass(String className, String input) throws Exception {
        try {
            // Load compiled class dynamically
            URLClassLoader classLoader = URLClassLoader.newInstance(new URL[]{new File(".").toURI().toURL()});
            Class<?> dynamicClass = Class.forName(className, true, classLoader);

            // Capture System.out output
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(outputStream));

            // Provide input using ByteArrayInputStream
            InputStream originalIn = System.in;
            System.setIn(new ByteArrayInputStream(input.getBytes()));

            // Execute the class's main method
            Method mainMethod = dynamicClass.getDeclaredMethod("main", String[].class);
            mainMethod.invoke(null, (Object) new String[]{});

            // Restore System.out and System.in
            System.setOut(originalOut);
            System.setIn(originalIn);

            return outputStream.toString();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InputMismatchException) {
                log.error("InputMismatchException - The input is of an incorrect type.", cause);
                throw new InputMismatchException("The input is of an incorrect type.");
            } else if (cause instanceof NoSuchElementException) {
                log.error("NoSuchElementException - Scanner tried to read but no input was provided.", cause);
                throw new NoSuchElementException("Scanner tried to read but no input was provided.");
            } else if (cause instanceof NullPointerException) {
                log.error("NullPointerException - A null object was accessed in the executed code.", cause);
                throw new NullPointerException("A null object was accessed in the executed code.");
            } else {
                log.error("Unexpected exception occurred: {}", cause.getMessage(), cause);
                throw new Exception("Error in execution: " + cause.getMessage(), cause);
            }
        }
    }

    public static String compileCCode(String cCode) throws Exception {
        try {// 1. Save code to a file
            String cFile = "solution.c";
            Files.write(Paths.get(cFile), cCode.getBytes());

            Process compile = new ProcessBuilder("g++", cFile, "-o", "solution").start();
            compile.waitFor();


            String line;
            BufferedReader stdErrReader = new BufferedReader(new InputStreamReader(compile.getErrorStream()));
            StringBuilder stderrOutput = new StringBuilder();
            while ((line = stdErrReader.readLine()) != null) {
                stderrOutput.append(line).append("\n");
            }

            if (!stderrOutput.toString().trim().isEmpty()) {
                return "Compilation Error: " + stderrOutput.toString().trim();
            }

            return null;

        } catch (Exception e) {

            log.error("Compilation Error: {}", e.getMessage(), e);
            throw new Exception("Compilation Error: " + e.getMessage());

        }

    }

    public static String runCCode(String input) throws Exception {
        log.info("Input: {}", input);
        input = input.trim().replaceAll(" +", "\n") + "\n";
        try {
            Process run = new ProcessBuilder("./solution").start();

            // 4. Write input to process
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(run.getOutputStream()));
            writer.write(input);
            writer.newLine();
            writer.flush();
            writer.close();

            BufferedReader reader = new BufferedReader(new InputStreamReader(run.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // Runtime errors from stderr
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(run.getErrorStream()));
            StringBuilder runtimeError = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                runtimeError.append(line).append("\n");
            }

            if (!runtimeError.toString().trim().isEmpty()) {
                return "Runtime Error:\n" + runtimeError.toString().trim();
            }
            log.info("Output: {}", output);
            log.error("Runtime Error: {}", runtimeError);

            return output.toString().trim();
        } catch (IOException e) {
            log.error("Solution.exe does not exist: {}", e.getMessage());
            throw new Exception("Solution.exe does not exist: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected exception occurred: {}", e.getMessage(), e);
            throw new Exception("Error in execution: " + e.getMessage());

        }
    }


    public static String compileCppCode(String cppCode) throws Exception {
        try {
            // Save code to file
            String cppFile = "solution.cpp";
            Files.write(Paths.get(cppFile), cppCode.getBytes());

            // Compile C++ code
            Process compile = new ProcessBuilder("g++", cppFile, "-o", "solution").start();
            compile.waitFor();

            BufferedReader stdErrReader = new BufferedReader(new InputStreamReader(compile.getErrorStream()));
            StringBuilder stderrOutput = new StringBuilder();
            String line;
            while ((line = stdErrReader.readLine()) != null) {
                stderrOutput.append(line).append("\n");
            }

            if (!stderrOutput.toString().trim().isEmpty()) {
                return "Compilation Error: " + stderrOutput.toString().trim();
            }

            return null;
        } catch (Exception e) {
            log.info("Compilation failed: {}", e.getMessage());
            throw new Exception("Compilation failed: " + e.getMessage(), e);
        }
    }

    public static String runCppCode(String input) throws Exception {
        log.info("Input: {}", input);
        input = input.trim().replaceAll(" +", "\n") + "\n";
        try {
            Process run = new ProcessBuilder("./solution").start();

            // Send input
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(run.getOutputStream()));
            writer.write(input);
            writer.newLine();
            writer.flush();
            writer.close();

            // Read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(run.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // Also read possible runtime errors from stderr
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(run.getErrorStream()));
            StringBuilder runtimeError = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                runtimeError.append(line).append("\n");
            }

            if (!runtimeError.toString().trim().isEmpty()) {
                return "Runtime Error:\n" + runtimeError.toString().trim();
            }

            return output.toString().trim();
        } catch (IOException e) {
            throw new Exception("Execution failed: " + e.getMessage(), e);
        }
    }


    public static String runPythonCode(String code, String input) throws Exception {
        log.info("Run Python Code: {}", code);
        log.info("Input: {}", input);

        input = input.trim().replaceAll(" +", "\n") + "\n";


        try (SharedInterpreter interp = new SharedInterpreter()) {
            // Setup simulated input and output
            interp.exec("import sys");
            interp.exec("from io import StringIO");
            interp.exec("import textwrap");

            interp.set("user_input", input);
            interp.set("code_str", code);

            interp.exec("sys.stdin = StringIO(user_input)");
            interp.exec("sys.stdout = StringIO()");
            interp.exec("sys.stderr = StringIO()");

//            interp.exec("exec(code_str)");
            interp.exec("exec(textwrap.dedent(code_str))");

            // Check for Python errors
            String errorOutput = (String) interp.getValue("sys.stderr.getvalue()");
            if (!errorOutput.isEmpty()) {
                return "ERROR: PythonError: " + errorOutput.trim();
            }

            String output = (String) interp.getValue("sys.stdout.getvalue()");
            return output.trim();

        } catch (jep.JepException e) {
            return "ERROR: JepError: " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected exception occurred: {}", e.getMessage(), e);
            throw new Exception("Error in execution: " + e.getMessage(), e);
        }


    }

    public static void cleanupGeneratedFiles() {
        // Java files
        new File("Solution.java").delete();
        new File("Solution.class").delete();

        // C files
        new File("Solution.cpp").delete();
        new File("Solution.c").delete();
        new File("Solution.exe").delete();
    }

//    public static void main(String[] args) throws Exception {
//        String cCode = """
//                #include <stdio.h>
//                int main() {
//                  int n;
//                  printf("Enter an integer: ");
//                  scanf("%d", &n);
//
//                  for (int i = 1; i <= 10; ++i) {
//                    printf("%d * %d = %d \\n", n, i, n * i);
//                  }
//                  return 0;
//                }
//                """;
//        String input = "6";
//        long runTimeStart = System.currentTimeMillis();
//        System.out.println(compileCCode(cCode));
//        long totalRunTime = System.currentTimeMillis() - runTimeStart;
//        System.out.println(totalRunTime);
//        long runTimeEnd = System.currentTimeMillis();
//
//        System.out.println(runCCode(input));
//        System.out.println(System.currentTimeMillis() - runTimeEnd);
//        cleanupGeneratedFiles();
//    }


//    public static void main(String[] args) throws Exception {
//        // These must come first
////        System.setProperty("jep.python.home", "C:\\Users\\ASHUTOSH\\AppData\\Local\\Programs\\Python\\Python39");
////
////        System.setProperty("jep.library.path", "C:\\Users\\ASHUTOSH\\AppData\\Local\\Programs\\Python\\Python39\\Lib\\site-packages\\jep");
//
//        String code = """
//                try:
//                    weight_str = input()
//                    height_str = input()
//
//                    weight = float(weight_str)
//                    height = float(height_str)
//
//                except ValueError:
//                    print("Invalid input")
//                    exit()
//
//                if not (0 < weight <= 300 and 0 < height <= 3):
//                    print("Invalid input")
//                else:
//                    bmi = weight / (height * height)
//                    status = ""
//                    if bmi < 18.5:
//                        status = "Underweight"
//                    elif bmi < 25:
//                        status = "Normal"
//                    elif bmi < 30:
//                        status = "Overweight"
//                    else:
//                        status = "Obese"
//
//                    print(f"BMI: {bmi:.2f} & Status: {status}")
//                """;
//
//        String input = "68 1.75";  // Simulate pressing Enter after input
//        String output = runPythonCode(code, input);
//        System.out.println("Python Output:\n" + output);
//    }

}

//    public static void main(String[] args) throws Exception {
//            // Example Java code
//            String code = "import java.util.Scanner; \n" +
//                    "  \n" +
//                    " public class Solution { \n" +
//                    "     public static void main(String[] args) { \n" +
//                    "         // Take help of Scanner to take input \n" +
//                    "         Scanner sc = new Scanner(System.in); \n" +
//                    "          \n" +
//                    "         // Take a number as input \n" +
//                    "         int dayNumber = sc.nextInt(); \n" +
//                    "          \n" +
//                    "         // Use switch statement and print the corresponding day of the week \n" +
//                    "         switch (dayNumber) { \n" +
//                    "             case 1: \n" +
//                    "                 System.out.println(\"Monday\"); \n" +
//                    "                 break; \n" +
//                    "             case 2: \n" +
//                    "                 System.out.println(\"Tuesday\"); \n" +
//                    "                 break; \n" +
//                    "             case 3: \n" +
//                    "                 System.out.println(\"Wednesday\"); \n" +
//                    "                 break; \n" +
//                    "             case 4: \n" +
//                    "                 System.out.println(\"Thursday\"); \n" +
//                    "                 break; \n" +
//                    "             case 5: \n" +
//                    "                 System.out.println(\"Friday\"); \n" +
//                    "                 break; \n" +
//                    "             case 6: \n" +
//                    "                 System.out.println(\"Saturday\"); \n" +
//                    "                 break; \n" +
//                    "             case 7: \n" +
//                    "                 System.out.println(\"Sunday\"); \n" +
//                    "                 break; \n" +
//                    "             default: \n" +
//                    "                 System.out.println(\"Invalid Input\"); \n" +
//                    "                 break; \n" +
//                    " \n" +
//                    "         } \n" +
//                    "     } \n" +
//                    " } ";
//
//            // Integer input (as a string, to simulate real input)
//            String userInput="5\n"; // Simulating user entering "5"
//
//            // Compile and run the Java code dynamically
//            String className = compileJavaCode(code);
//            String output = runCompiledClass(className, userInput);
//
//            // Print execution result
//            System.out.println("Execution Output:\n" + output);
//        }



