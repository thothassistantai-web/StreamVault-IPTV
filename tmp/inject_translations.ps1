# inject_translations.ps1
# Reads translation JSON files and injects missing strings into each locale's strings.xml

$ErrorActionPreference = "Stop"

$resDir = "c:\Users\david\.gemini\antigravity\scratch\iptv-player\app\src\main\res"
$chatResDir = "c:\Users\david\AppData\Roaming\Code\User\workspaceStorage\cc954ace92abe3dd58fef056c4c840c5\GitHub.copilot-chat\chat-session-resources\4689e49b-8fc4-49dd-b09d-540ac637d9a5"

# Map each translation file to the languages it contains
$translationFiles = @(
    @{ File = "toolu_01ReUoN1ZJY5beNpmBVjMbzW__vscode-1778309660660\content.txt"; Langs = @("ar") },
    @{ File = "toolu_01KUAXXcHyLctB6wEY2EQJeA__vscode-1778309660663\content.txt"; Langs = @("de", "fr") },
    @{ File = "toolu_01W8aXAbWZxpHwsUL23FRRnG__vscode-1778309660667\content.txt"; Langs = @("es", "ru") },
    @{ File = "toolu_01SWPh5bzAQy9WMTfm7oeqm3__vscode-1778309660668\content.txt"; Langs = @("it", "pt") },
    @{ File = "toolu_0117s5ydT8wK1ooWTuvgtZyj__vscode-1778309660669\content.txt"; Langs = @("ja", "ko") },
    @{ File = "toolu_01955EP27uEpy2EGWm3v8wZm__vscode-1778309660670\content.txt"; Langs = @("cs", "pl") },
    @{ File = "toolu_01XfsMkYxFvUZ8h5v21akZap__vscode-1778309660673\content.txt"; Langs = @("tr") },
    @{ File = "toolu_01Tp7VexKe6EDQ6rMYqnpvB7__vscode-1778309660674\content.txt"; Langs = @("uk") },
    @{ File = "toolu_01B7wokmTcFpg34btGyKSH6M__vscode-1778309660675\content.txt"; Langs = @("el", "fi") },
    @{ File = "toolu_01CpzMmCJ2bbWLL9tJaYcAL8__vscode-1778309660676\content.txt"; Langs = @("hu", "in") },
    @{ File = "toolu_01WDoZLQVHLadSacrKwBBbQu__vscode-1778309660680\content.txt"; Langs = @("iw", "ro") },
    @{ File = "toolu_01Rku1YjRBnh4Yia3ANL3bB7__vscode-1778309660682\content.txt"; Langs = @("vi", "zh") },
    @{ File = "toolu_01CZxuhTgAkZ48782T7reams__vscode-1778309660683\content.txt"; Langs = @("da") },
    @{ File = "toolu_018efTx5mw2wVKVqE1sSNvDq__vscode-1778309660684\content.txt"; Langs = @("nl") },
    @{ File = "toolu_01RBg6Ji6vEuHrBv5DJdHSGX__vscode-1778309660685\content.txt"; Langs = @("nb") },
    @{ File = "toolu_01G7QWLHpngWnzGrs4BVF3wS__vscode-1778309660686\content.txt"; Langs = @("sv") }
)

# Plural keys
$pluralKeys = @("settings_live_tv_quick_filters_count", "settings_timeout_seconds", "settings_timer_minutes")

function Escape-XmlValue($s) {
    $s = $s -replace '&', '&amp;'
    $s = $s -replace "'", "\'"
    # Leave quotes as-is in string content (Android allows unescaped quotes in string values)
    return $s
}

function Build-XmlLines($key, $value) {
    if ($pluralKeys -contains $key) {
        # value should be a hashtable with 'one' and 'other'
        $one = Escape-XmlValue $value.one
        $other = Escape-XmlValue $value.other
        return @(
            "    <plurals name=`"$key`">",
            "        <item quantity=`"one`">$one</item>",
            "        <item quantity=`"other`">$other</item>",
            "    </plurals>"
        )
    } else {
        $escaped = Escape-XmlValue $value
        return @("    <string name=`"$key`">$escaped</string>")
    }
}

# Load all translations into a single hashtable keyed by lang -> key -> value
$allTranslations = @{}

foreach ($entry in $translationFiles) {
    $filePath = Join-Path $chatResDir $entry.File
    $rawContent = Get-Content $filePath -Raw -Encoding UTF8
    
    # Strip markdown code fences if present
    $rawContent = $rawContent -replace '(?s)^```json\s*', '' -replace '(?s)```\s*$', ''
    $rawContent = $rawContent.Trim()
    
    $parsed = $rawContent | ConvertFrom-Json
    
    foreach ($lang in $entry.Langs) {
        # Some files have the lang key as wrapper, some (ar) are flat
        $langData = $parsed.$lang
        if ($null -eq $langData) {
            # Try flat (no wrapper) - only valid for single-lang files
            if ($entry.Langs.Count -eq 1) {
                $langData = $parsed
            }
        }
        if ($null -eq $langData) {
            Write-Warning "Language '$lang' not found in $($entry.File)"
            continue
        }
        $dict = @{}
        $langData.PSObject.Properties | ForEach-Object {
            $k = $_.Name
            $v = $_.Value
            if ($v -is [System.Management.Automation.PSCustomObject]) {
                # Plural - convert to hashtable
                $dict[$k] = @{ one = $v.one; other = $v.other }
            } else {
                $dict[$k] = $v
            }
        }
        $allTranslations[$lang] = $dict
    }
}

Write-Host "Loaded translations for: $($allTranslations.Keys -join ', ')"

# Process each locale
$locales = $allTranslations.Keys | Sort-Object

foreach ($lang in $locales) {
    $xmlPath = Join-Path $resDir "values-$lang\strings.xml"
    if (-not (Test-Path $xmlPath)) {
        Write-Warning "File not found: $xmlPath"
        continue
    }
    
    $dict = $allTranslations[$lang]
    
    # Read existing XML to find already-present keys
    $existingContent = Get-Content $xmlPath -Raw -Encoding UTF8
    
    # Build lines to insert
    $linesToInsert = [System.Collections.Generic.List[string]]::new()
    
    foreach ($key in ($dict.Keys | Sort-Object)) {
        # Check if key already exists in file
        if ($existingContent -match [regex]::Escape("name=`"$key`"")) {
            # Already present, skip
            continue
        }
        $xmlLines = Build-XmlLines $key $dict[$key]
        foreach ($line in $xmlLines) {
            $linesToInsert.Add($line)
        }
    }
    
    if ($linesToInsert.Count -eq 0) {
        Write-Host "$lang : no new keys to inject"
        continue
    }
    
    # Insert before </resources>
    $insertBlock = ($linesToInsert -join "`n") + "`n"
    $newContent = $existingContent -replace '</resources>', "$insertBlock</resources>"
    
    # Write back with UTF-8 (no BOM to match Android convention)
    [System.IO.File]::WriteAllText($xmlPath, $newContent, [System.Text.UTF8Encoding]::new($false))
    
    Write-Host "$lang : injected $($linesToInsert.Count) lines"
}

Write-Host "`nDone!"
