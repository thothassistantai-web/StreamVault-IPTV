$root = "C:\Users\david\.gemini\antigravity\scratch\iptv-player"
$en = [xml](Get-Content "$root\app\src\main\res\values\strings.xml" -Encoding UTF8 -Raw)
$missing = @(Get-Content "$root\missing_keys.txt" | Where-Object {$_.Trim() -ne ''})
$lines = @()
foreach ($key in $missing) {
    $key = $key.Trim()
    $node = $en.resources.ChildNodes | Where-Object {$_.name -eq $key} | Select-Object -First 1
    if ($node) {
        $type = $node.LocalName
        if ($type -eq 'string') {
            $val = $node.InnerText -replace '"','\"'
            $lines += "${key}`t${val}"
        } elseif ($type -eq 'plurals') {
            foreach ($item in $node.item) {
                $val = $item.InnerText -replace '"','\"'
                $q = $item.quantity
                $lines += "${key}[${q}]`t${val}"
            }
        }
    }
}
$lines | Out-File "$root\missing_en.tsv" -Encoding UTF8
Write-Host "Done: $($lines.Count) lines"
