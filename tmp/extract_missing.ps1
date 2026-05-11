$root = "C:\Users\david\.gemini\antigravity\scratch\iptv-player"
$en = [xml](Get-Content "$root\app\src\main\res\values\strings.xml" -Encoding UTF8 -Raw)
$missing = Get-Content "$root\missing_keys.txt"
$result = @()
foreach ($key in $missing) {
    $node = $en.resources.ChildNodes | Where-Object {$_.name -eq $key}
    if ($node) {
        $type = $node.LocalName
        if ($type -eq 'string') {
            $result += [PSCustomObject]@{key=$key; type='string'; value=$node.'#text'}
        } elseif ($type -eq 'plurals') {
            $items = @($node.item) | ForEach-Object { "$($_.quantity):$($_.'#text')" }
            $result += [PSCustomObject]@{key=$key; type='plurals'; value=($items -join '|')}
        }
    }
}
$result | ConvertTo-Json -Depth 5 | Out-File "$root\missing_en.json" -Encoding UTF8
Write-Host "Done: $($result.Count) entries"
