package cc.ruok.cang.api;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次编译的结果：是否成功、结构化诊断、产物路径与 IR 文本。
 */
public class CompileResult {

    /** 全流程无错误（含链接）为 true。 */
    public boolean success;

    /** 错误列表；源码错误带行列，构建/链接错误为 0:0。成功时为空。 */
    public List<Diagnostic> diagnostics = new ArrayList<>();

    /** .ll 文件路径（成功或 --no-link 时非空）。 */
    public String llFile;

    /** 可执行文件路径（完成链接时非空；windows 以 .exe 结尾）。 */
    public String exeFile;

    /** 生成的 LLVM IR 文本（.ll 可读时填充，便于 IDE 预览）。 */
    public String irText;

    /** 链接失败时捕获的工具链输出（clang/gcc 原文），否则为 null。 */
    public String buildOutput;

    public boolean isSuccess() {
        return success;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public String getLlFile() {
        return llFile;
    }

    public String getExeFile() {
        return exeFile;
    }

    public String getIrText() {
        return irText;
    }

    public String getBuildOutput() {
        return buildOutput;
    }
}
