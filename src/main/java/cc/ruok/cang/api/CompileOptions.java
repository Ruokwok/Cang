package cc.ruok.cang.api;

/**
 * {@link CangApi#compile(String, CompileOptions)} 的构建选项（fluent，全部可选）。
 * 默认：windows / amd64 / GC 开 / 链接 / 静默。
 */
public class CompileOptions {

    /** windows | linux | macos，默认 windows。 */
    private String target = "windows";
    private String arch = "amd64";
    private boolean gc = true;
    private boolean noLink = false;
    /** GUI 子系统（PE subsystem=GUI，不弹控制台）；仅 windows 目标生效，linux ELF 无此概念。 */
    private boolean gui = false;
    /** 可执行文件名（.exe 可省）；null = 与源文件同名。 */
    private String outputName = null;
    private String clangPath = "clang";
    private String gccPath = "gcc";
    /** 产物根目录：产物输出到 <targetRoot>/target/ 下；null = 进程 cwd 的 target/。 */
    private String targetRoot = null;
    /** 库根目录：在其 lib/ 下查找所需 .a（GC 及将来的其它库）。 */
    private String libRoot = null;
    /** Linux sysroot 目录；null = 默认 <jar 所在目录>/../lib/sysroot_linux_<arch>。 */
    private String sysroot = null;
    /** true（默认）= 编译过程不向 stdout 打印；false = 与 CLI 相同的进度输出。 */
    private boolean quiet = true;

    public CompileOptions target(String t) { this.target = t; return this; }
    public CompileOptions arch(String a) { this.arch = a; return this; }
    public CompileOptions gc(boolean on) { this.gc = on; return this; }
    public CompileOptions gui(boolean on) { this.gui = on; return this; }
    public CompileOptions noLink(boolean on) { this.noLink = on; return this; }
    public CompileOptions outputName(String name) { this.outputName = name; return this; }
    public CompileOptions targetRoot(String dir) { this.targetRoot = (dir == null || dir.isBlank()) ? null : dir; return this; }
    public CompileOptions clang(String path) { this.clangPath = path; return this; }
    public CompileOptions gcc(String path) { this.gccPath = path; return this; }
    public CompileOptions libRoot(String dir) { this.libRoot = dir; return this; }
    public CompileOptions sysroot(String dir) { this.sysroot = dir; return this; }
    public CompileOptions quiet(boolean q) { this.quiet = q; return this; }

    public String getTarget() { return target; }
    public String getArch() { return arch; }
    public boolean isGc() { return gc; }
    public boolean isGui() { return gui; }
    public boolean isNoLink() { return noLink; }
    public String getOutputName() { return outputName; }
    public String getTargetRoot() { return targetRoot; }
    public String getClangPath() { return clangPath; }
    public String getGccPath() { return gccPath; }
    public String getLibRoot() { return libRoot; }
    public String getSysroot() { return sysroot; }
    public boolean isQuiet() { return quiet; }
}
