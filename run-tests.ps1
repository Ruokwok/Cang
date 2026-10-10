# run-tests.ps1 — minimal system test runner for the Cang compiler.
#
# Runs the full regression suite (compile + run + negative compile checks)
# against target/classes. Tests live under test/ (gitignored, local-only).
#
# Usage:
#   powershell -File run-tests.ps1                 # full suite (125)
#   powershell -File run-tests.ps1 -Filter t_dict  # run matching tests only
#   powershell -File run-tests.ps1 -JavaPath D:\...\java.exe
#
# Exit code: 0 = all green, 1 = failures, 2 = environment problem.
param(
    [string]$Filter = "",
    [string]$JavaPath = "",
    [string]$Entry = "cc.ruok.cang.Cang"
)

$ErrorActionPreference = "Stop"
$root = if ($PSScriptRoot) { $PSScriptRoot } else { Get-Location }

# ---- java resolution: -JavaPath > PATH > known JDK install ----
if (-not $JavaPath) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $JavaPath = $cmd.Source }
    else {
        $fallback = "D:\Program Files\.jdks\openjdk-17.0.2\bin\java.exe"
        if (Test-Path $fallback) { $JavaPath = $fallback }
    }
}
if (-not $JavaPath -or -not (Test-Path $JavaPath)) {
    Write-Host "ERROR: java not found (use -JavaPath)"
    exit 2
}
if (-not (Test-Path (Join-Path $root "target\classes\$($Entry.Replace('.','\')).class"))) {
    Write-Host "ERROR: target\classes is not built (run mvn compile or javac first)"
    exit 2
}

# ---- process helpers (stdout+stderr, timeout-safe) ----
function Invoke-Proc([string]$file, [string]$argsStr, [int]$timeoutMs = 60000) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new($file)
    $psi.Arguments = $argsStr; $psi.WorkingDirectory = $root
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    $null = $p.WaitForExit($timeoutMs)
    if (-not $p.HasExited) { $p.Kill(); return @{ exit = -99; out = "TIMEOUT" } }
    $null = $outTask.Wait(3000); $null = $errTask.Wait(3000)
    return @{ exit = $p.ExitCode; out = $outTask.Result + $errTask.Result }
}

function Invoke-Stdin([string]$file, [string[]]$writeLines, [bool]$close, [int]$timeoutMs = 30000) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new($file)
    $psi.Arguments = ""; $psi.WorkingDirectory = $root
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $psi.RedirectStandardInput = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    foreach ($l in $writeLines) { $p.StandardInput.WriteLine($l) }
    if ($close) { $p.StandardInput.Close() }
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    $null = $p.WaitForExit($timeoutMs)
    if (-not $p.HasExited) { $p.Kill(); return @{ exit = -99; out = "TIMEOUT" } }
    if (-not $close) { $p.StandardInput.Close() }
    $null = $outTask.Wait(3000); $null = $errTask.Wait(3000)
    return @{ exit = $p.ExitCode; out = $outTask.Result + $errTask.Result }
}

# ---- suite definition (125) ----
# pos  = compile must succeed, run must exit 0
# err  = compile must succeed, run must exit non-zero (runtime error)
# neg  = compile must fail (--no-link)
# scan = compile + run with scripted stdin, run must exit 0
$pos = @("arr_syntax","arr_class","arr_nested","arr_assign","function_full","try_basic",
         "list_demo","list_int","list_methods","generic_list","gc_demo","system_gc",
         "thread_demo","thread_types","thread_gc","thread_block","test_args","file_std","file_sep",
         "t_strnull","t_divzero_catch","t_rec_ok","t_loopalloc","t_feloop","t_arraylist",
         "t_lambda_capture","t_shortcircuit","t_stderr","t_retpath","t_overload","t_thread_obj",
         "t_generic_pair","t_dict","t_foreach_var","t_import_as","t_import_as2",
         "t_fin_return","t_fin_rethrow","t_generic_null","t_free_objs","t_abstract",
         "t_top_level","t_lambda_ret","t_lambda_multi","t_thread_wait","t_thread_daemon",
         "thread_capture","thread_thisref","thread_block_loop","thread_loop","t_compound",
         "t_num_lit","t_switch","t_print_obj","t_sys_exit","t_free_file","t_freed_scope",
         "t_range_ok","t_print_bool","t_private_ctor","t_singleton","t_string","t_parse","t_math2",
         "t_process","t_process_missing","t_process_to","t_sys_cwd","t_str_utf8","t_sys_execdir",
         "t_stringbuilder")
$err = @("t_nullfield","t_nullfield_w","t_nullfield_chain","t_divzero","t_stackoverflow",
         "t_arraylist_bounds","t_arrwrite","t_arrnull","t_thread_obj_err2","t_dict_err",
         "thread_double_join","t_parse_err","t_parse_range_err")
$neg = @("thread_reject","t_typecheck","t_retpath_missing","t_retpath_bool","t_overload_default",
         "t_thread_obj_err","t_generic_pair_err","t_foreach_mismatch","t_import_as_err","t_stmt_lost",
         "t_lex_unterm_str","t_lex_unterm_cmt","t_lex_unterm_tick","t_lex_lineshift",
         "t_abstract_inst","t_abstract_missing","t_abstract_body","t_stmt_before_class",
         "t_lambda_ret_noexp","t_lambda_ret_mismatch","t_lambda_ret_missing",
         "t_lambda_dup","t_lambda_arity","t_num_escape",
         "t_switch_dup","t_switch_typeline","t_inc_line","t_freed_dup",
         "t_dup_class","t_dup_var","t_range_int","t_range_byte","t_range_narrow","t_range_assign",
         "t_deep_nest","t_private_new")
$scanner = @(
    @{ t = "t_scanner";      write = @("hello","world"); close = $false },
    @{ t = "t_scanner_eof";  write = @();                 close = $true  },
    @{ t = "t_scanner_to";   write = @();                 close = $false },
    @{ t = "t_scanner_rl_to";write = @();                 close = $false },
    @{ t = "t_scanner_ch_to";write = @();                 close = $false }
)

# ---- optional subset filter ----
if ($Filter -ne "") {
    $pos = @($pos  | Where-Object { $_ -like "*$Filter*" })
    $err = @($err  | Where-Object { $_ -like "*$Filter*" })
    $neg = @($neg  | Where-Object { $_ -like "*$Filter*" })
    $scanner = @($scanner | Where-Object { $_.t -like "*$Filter*" })
}

$failed = @(); $passed = 0

foreach ($t in $pos) {
    $c = Invoke-Proc $JavaPath "-cp target\classes $Entry test\$t.cang"
    if ($c.exit -ne 0) { $failed += "$t COMPILE"; continue }
    $r = Invoke-Proc (Join-Path $root "target\$t.exe") ""
    if ($r.exit -ne 0) { $failed += "$t RUN exit=$($r.exit)" } else { $passed++ }
}
foreach ($t in $err) {
    $c = Invoke-Proc $JavaPath "-cp target\classes $Entry test\$t.cang"
    if ($c.exit -ne 0) { $failed += "$t COMPILE"; continue }
    $r = Invoke-Proc (Join-Path $root "target\$t.exe") ""
    if ($r.exit -eq 0) { $failed += "$t expected error" } else { $passed++ }
}
foreach ($t in $neg) {
    $c = Invoke-Proc $JavaPath "-cp target\classes $Entry test\$t.cang --no-link"
    if ($c.exit -eq 0) { $failed += "$t NEG" } else { $passed++ }
}
foreach ($spec in $scanner) {
    $c = Invoke-Proc $JavaPath "-cp target\classes $Entry test/$($spec.t).cang"
    if ($c.exit -ne 0) { $failed += "$($spec.t) COMPILE"; continue }
    $r = Invoke-Stdin (Join-Path $root "target\$($spec.t).exe") $spec.write $spec.close
    if ($r.exit -ne 0) { $failed += "$($spec.t) RUN exit=$($r.exit)" } else { $passed++ }
}

$total = $pos.Count + $err.Count + $neg.Count + $scanner.Count
Write-Host "TOTAL: $passed / $total"
if ($failed.Count -eq 0) {
    Write-Host "=== ALL GREEN ==="
    exit 0
} else {
    $failed | ForEach-Object { Write-Host "FAIL $_" }
    exit 1
}
