import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.ShrikeBTMethod;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.cha.CHACallGraph;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.annotations.Annotation;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.AnalysisScopeReader;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

public class TCSelection {

    // configuration files
    private final String targetPath;
    private final String changeInfoPath;
    //private final String scopeFilePath = "E:\\courses\\自动化测试\\wala\\WALA\\src\\main\\resources\\scope.txt";
    //private final String exclusionFilePath = "E:\\courses\\自动化测试\\wala\\WALA\\src\\main\\resources\\exclusion.txt";
    private final String scopeFilePath = "D:\\NJU\\study\\软工专业课\\自动化测试\\wala\\WALA\\src\\main\\resources\\scope.txt";
    private final String exclusionFilePath = "D:\\NJU\\study\\软工专业课\\自动化测试\\wala\\WALA\\src\\main\\resources\\exclusion.txt";

    private final HashSet<String> allClasses = new HashSet<String>();  //所有检测到的类
    private final HashSet<String> testMethods = new HashSet<String>(); //所有注解为test的方法
    private final HashSet<String> allMethods = new HashSet<String>();  //所有追踪到的方法
    private final HashMap<String, List<String>> callRelation = new HashMap<String, List<String>>(); // key为方法，value为所有直接调用了key方法的方法
    private final ArrayList<String> changeInfo = new ArrayList<String>(); // 记录所有的变更信息

    private final HashMap<String, List<String>> classDependencies = new HashMap<>();  // 类级别依赖信息，key为类名，value为所有直接依赖该类的类名
    private final HashMap<String, List<String>> methodDependencies = new HashMap<>(); // 方法级别依赖，key为方法名，value为所有直接依赖该方法的方法名

    /**
     * 构造函数
     *
     * @param targetPath     待分析的文件所在的路径
     * @param changeInfoPath 变更信息文件所在的路径
     */
    public TCSelection(String targetPath, String changeInfoPath) {
        this.targetPath = targetPath;
        this.changeInfoPath = changeInfoPath;
    }

    /**
     * 获取所有分析相关的类，改写自readme中的代码
     */
    public void getAllClasses(CHACallGraph cg) {
        for (CGNode node : cg) {
            if (node.getMethod() instanceof ShrikeBTMethod) {
                ShrikeBTMethod method = (ShrikeBTMethod) node.getMethod();
                if ("Application".equals(method.getDeclaringClass().getClassLoader().toString())) {
                    String classInnerName = method.getDeclaringClass().getName().toString();
                    this.allClasses.add(classInnerName.split("\\$")[0]); // 对于有内部类的只记录其主类是什么
                }
            }
        }
    }

    /**
     * 构建总的调用关系，key为"类名 方法名"，value为依赖了key的所有"类名 方法名"集合
     * 遍历的方法改写与readme中的方法
     */
    public void buildCallRelation(CHACallGraph cg) throws InvalidClassFileException {
        // 遍历cg中所有的节点
        for (CGNode node : cg) {
            // node中包含了很多信息，包括类加载器、方法信息等，这里只筛选出需要的信息
            if (node.getMethod() instanceof ShrikeBTMethod) {
                // node.getMethod()返回一个比较泛化的IMethod实例，不能获取到我们想要的信息
                // 一般地，本项目中所有和业务逻辑相关的方法都是ShrikeBTMethod对象
                ShrikeBTMethod method = (ShrikeBTMethod) node.getMethod();
                // 使用Primordial类加载器加载的类都属于Java原生类，我们一般不关心。
                if ("Application".equals(method.getDeclaringClass().getClassLoader().toString())) {
                    // 获取声明该方法的类的内部表示
                    String classInnerName = method.getDeclaringClass().getName().toString();
                    // 获取方法签名
                    String signature = method.getSignature();
                    String caller = classInnerName + " " + signature;

                    // 根据注解判断该method是否为一个测试的方法
                    String pattern = "Annotation type <Application,Lorg/junit/Test>.*";
                    for (Annotation annotationType : method.getAnnotations()) {
                        if (Pattern.matches(pattern, annotationType.toString())) {
                            testMethods.add(caller);
                            break;
                        }
                    }

                    // 遍历该method调用了哪些方法
                    for (CallSiteReference m : method.getCallSites()) {
                        // 分离出类名
                        String calledClass = m.getDeclaredTarget().toString().replace(" ", "").split(",")[1].split("\\$")[0];
                        // 分离出方法名
                        String calledMethod = m.getDeclaredTarget().getSignature();
                        String callee = calledClass + " " + calledMethod;

                        if (callRelation.containsKey(callee)) {
                            if (!callRelation.get(callee).contains(caller)) {
                                callRelation.get(callee).add(caller);
                            }
                        } else {
                            List<String> list = new ArrayList<String>();
                            list.add(caller);
                            callRelation.put(callee, list);
                        }
                    }
                }
            }
        }
        /*
        System.out.println("show call relation");
        for (String callee : callRelation.keySet()) {
            System.out.println(callee);
            System.out.println("called by:");
            for (String s : callRelation.get(callee)) {
                System.out.println(s);
            }
            System.out.println();
        }
        */

        //将callRelation中的所有方法拿出来放到allMethods中
        for (String callee : callRelation.keySet()) {
            allMethods.add(callee);
            allMethods.addAll(callRelation.get(callee));
        }
    }

    /**
     * class粒度的测试用例选择
     */
    public void selectByClass() {
        /**
         * 先找到所有有关联的类，然后去allMethods中找到那些对应的方法
         */
        HashSet<String> relatedClasses = new HashSet<String>();
        HashSet<String> selectedMethods = new HashSet<String>();
        for (String s : changeInfo) {
            String searchClass = s.split(" ")[0];
            relatedClasses.add(searchClass);
            selectByClass_r(relatedClasses, searchClass);
        }

        for (String s : allMethods) {
            if (relatedClasses.contains(s.split(" ")[0])) {
                selectedMethods.add(s);
            }
        }
        this.storeSelectedMethods(selectedMethods, "./selection-class.txt");
    }

    /**
     * 递归地寻找所有依赖了(直接or间接)searchClass的class并将其加入到relatedClasses中
     *
     * @param relatedClasses 记录所有依赖了searchClass的class
     * @param searchClass    待搜索的class
     */
    public void selectByClass_r(HashSet<String> relatedClasses, String searchClass) {
        for (String callee : callRelation.keySet()) {
            if (callee.split(" ")[0].equals(searchClass)) {
                for (String caller : callRelation.get(callee)) {
                    relatedClasses.add(caller.split(" ")[0]);
                    if (relatedClasses.contains(caller.split(" ")[0]))
                        continue;
                    // 递归去寻找依赖了caller的class，即寻找间接依赖了searchClass的class
                    selectByClass_r(relatedClasses, caller.split(" ")[0]);
                }
            }
        }
    }

    /**
     * method粒度的测试用例选择
     */
    public void selectByMethod() {
        HashSet<String> selectedMethods = new HashSet<String>();
        for (String s : changeInfo) {
            HashSet<String> relatedMethods = new HashSet<String>();
            selectByMethod_r(relatedMethods, s);
            selectedMethods.addAll(relatedMethods);
        }
        this.storeSelectedMethods(selectedMethods, "./selection-method.txt");
    }

    /**
     * 递归地寻找所有依赖了(直接or间接)searchMethod的method并将其加入到relatedMethod中
     *
     * @param relatedMethod 记录所有依赖了searchMethod的method
     * @param searchMethod  待搜索的method
     */
    public void selectByMethod_r(HashSet<String> relatedMethod, String searchMethod) {
        HashSet<String> newMethod = new HashSet<String>();
        // 如果该方法并没有记录过，那就跳过，以免出现空指针的问题
        if (!this.callRelation.containsKey(searchMethod))
            return;
        for (String s : this.callRelation.get(searchMethod)) {
            if (!relatedMethod.contains(s)) {
                relatedMethod.add(s);
                newMethod.add(s);
            }
        }
        for (String s : newMethod) {
            selectByMethod_r(relatedMethod, s);
        }
    }

    /**
     * 将选取出来的测试用例集合输出到文件
     *
     * @param selectedMethods 选择出来的测试用例的集合
     * @param filename        存储到的文件的名称
     */
    public void storeSelectedMethods(HashSet<String> selectedMethods, String filename) {
        try {
            File file = new File(filename);
            FileWriter fileWriter = new FileWriter(file);
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
            for (String string : selectedMethods) {
                // 只选择测试方法
                if (testMethods.contains(string))
                    bufferedWriter.write(string + "\n");
            }
            bufferedWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 找到每个class被哪些class直接依赖
     */
    public void getClassDependencies() {
        for (String s : callRelation.keySet()) {
            String callee = s.split(" ")[0];
            if (!allClasses.contains(callee))
                continue;
            // 如果该类之前还没出现过，那就初始化一下
            if (!classDependencies.containsKey(callee)) {
                List<String> list = new ArrayList<>();
                classDependencies.put(callee, list);
            }
            for (String string : callRelation.get(s)) {
                String caller = string.split(" ")[0];
                if (!classDependencies.get(callee).contains(caller)) {
                    if (!allClasses.contains(caller))
                        continue;
                    classDependencies.get(callee).add(caller);
                }
            }
        }
        this.storeClassDependDotFile("./class-dependencies.dot");
    }

    /**
     * 找到每个method被哪些method直接依赖
     */
    public void getMethodDependencies() {
        for (String s : callRelation.keySet()) {
            String callee = s.split(" ")[1];
            if (!allClasses.contains(s.split(" ")[0]))
                continue;
            if (!methodDependencies.containsKey(callee)) {
                List<String> list = new ArrayList<>();
                methodDependencies.put(callee, list);
            }
            for (String string : callRelation.get(s)) {
                String caller = string.split(" ")[1];
                if (!methodDependencies.get(callee).contains(caller)) {
                    if (!allClasses.contains(string.split(" ")[0]))
                        continue;
                    methodDependencies.get(callee).add(caller);
                }
            }
        }
        this.storeMethodDependDotFile("./method-dependencies.dot");
    }

    public void storeClassDependDotFile(String filename) {
        try {
            File file = new File(filename);
            FileWriter fileWriter = new FileWriter(file);
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
            bufferedWriter.write("digraph dependencies {\n");
            for (String string : classDependencies.keySet()) {
                for (String s : classDependencies.get(string)) {
                    bufferedWriter.write("\t");
                    bufferedWriter.write("\"" + string + "\" -> \"" + s + "\";\n");
                }
            }
            bufferedWriter.write("}");
            bufferedWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void storeMethodDependDotFile(String filename) {
        try {
            File file = new File(filename);
            FileWriter fileWriter = new FileWriter(file);
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
            bufferedWriter.write("digraph dependencies {\n");
            for (String string : methodDependencies.keySet()) {
                for (String s : methodDependencies.get(string)) {
                    bufferedWriter.write("\t");
                    bufferedWriter.write("\"" + string + "\" -> \"" + s + "\";\n");
                }
            }
            bufferedWriter.write("}");
            bufferedWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void select(char selectOption) throws IOException, InvalidClassFileException, ClassHierarchyException, CancelException {
        String sourcedirPath = this.targetPath + "\\classes\\net\\mooctest";
        String testdirPath = this.targetPath + "\\test-classes\\net\\mooctest";

        File sourcedir = new File(sourcedirPath);
        File testdir = new File(testdirPath);
        File[] sourceFiles = sourcedir.listFiles();
        File[] testFiles = testdir.listFiles();

        ClassLoader classLoader = TCSelection.class.getClassLoader();

        AnalysisScope scope = AnalysisScopeReader.readJavaScope(this.scopeFilePath, new File(this.exclusionFilePath), classLoader);

        // 需要添加所有需要验证的类
        if (sourceFiles != null) {
            for (File f : sourceFiles) {
                scope.addClassFileToScope(ClassLoaderReference.Application, f);
            }
        }
        if (testFiles != null) {
            for (File f : testFiles) {
                scope.addClassFileToScope(ClassLoaderReference.Application, f);
            }
        }

        // 1.生成类层次关系对象
        ClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);
        // 2.生成进入点
        Iterable<Entrypoint> eps = new AllApplicationEntrypoints(scope, cha);
        // 3.利用CHA算法构建调用图
        CHACallGraph cg = new CHACallGraph(cha);
        cg.init(eps);

        // 提取所有相关的类
        this.getAllClasses(cg);

        // 构建调用关系
        this.buildCallRelation(cg);

        // 读取变更信息
        try {
            BufferedReader in = new BufferedReader(new FileReader(this.changeInfoPath));
            String line;
            while ((line = in.readLine()) != null) {
                this.changeInfo.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (selectOption == 'c') {
            this.selectByClass();
        } else if (selectOption == 'm') {
            this.selectByMethod();
        } else {
            System.err.println("Unknown select option" + selectOption);
        }

        this.getClassDependencies();

        this.getMethodDependencies();
    }

    public static void main(String[] args) throws IOException, InvalidClassFileException, ClassHierarchyException, CancelException {
        TCSelection ts = new TCSelection(args[1], args[2]);
        ts.select(args[0].charAt(1));
    }
}
