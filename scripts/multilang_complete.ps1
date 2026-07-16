# Multi-language completion script (iteration #13)
# For de/es/ja/ko/ru/fr, fill missing strings keys with en fallback
# Marked with TODO: translate for future translators

$base = 'e:\PhtonView\app\src\main\res'
$enFile = "$base\values\strings.xml"
$langs = @('values-de', 'values-es', 'values-fr', 'values-ja', 'values-ko', 'values-ru')

function Get-Strings($path) {
    $result = @{}
    $content = Get-Content $path -Raw
    $regex = [regex]'<string\s+name="([^"]+)"[^>]*>(.*?)</string>'
    $matches_found = $regex.Matches($content)
    foreach ($m in $matches_found) {
        $name = $m.Groups[1].Value
        $value = $m.Groups[2].Value
        $value = $value -replace '&amp;', '&'
        $value = $value -replace '&lt;', '<'
        $value = $value -replace '&gt;', '>'
        $value = $value -replace '&quot;', '"'
        $value = $value -replace '&apos;', "'"
        $result[$name] = $value
    }
    return $result
}

function Append-Missing($path, $enMap, $langName) {
    $existing = Get-Strings $path
    $missing = @()
    foreach ($name in $enMap.Keys) {
        if (-not $existing.ContainsKey($name)) {
            $missing += $name
        }
    }
    if ($missing.Count -eq 0) {
        Write-Host "[$langName] no missing keys"
        return
    }
    Write-Host "[$langName] filling $($missing.Count) missing keys (fallback to en)"
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('    [!-- iteration #13: auto-filled fallback to en for ' + $langName + '. TODO: translate. --]')
    foreach ($name in $missing) {
        $value = $enMap[$name]
        $escaped = $value -replace '&', '&amp;'
        $escaped = $escaped -replace '<', '&lt;'
        $escaped = $escaped -replace '>', '&gt;'
        $escaped = $escaped -replace '"', '&quot;'
        $escaped = $escaped -replace "'", '&apos;'
        [void]$sb.AppendLine('    <string name="' + $name + '">' + $escaped + '</string>')
    }
    Add-Content -Path $path -Value $sb.ToString() -Encoding UTF8
}

$enMap = Get-Strings $enFile
Write-Host ('Loaded ' + $enMap.Count + ' en keys')

foreach ($lang in $langs) {
    $path = "$base\$lang\strings.xml"
    if (Test-Path $path) {
        Append-Missing $path $enMap $lang
    } else {
        Write-Host "[$lang] FILE NOT FOUND, creating"
        $sb = New-Object System.Text.StringBuilder
        [void]$sb.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
        [void]$sb.AppendLine('<resources>')
        [void]$sb.AppendLine('    [!-- iteration #13: auto-generated for ' + $lang + ', fallback to en. TODO: translate. --]')
        foreach ($name in $enMap.Keys) {
            $value = $enMap[$name]
            $escaped = $value -replace '&', '&amp;'
            $escaped = $escaped -replace '<', '&lt;'
            $escaped = $escaped -replace '>', '&gt;'
            $escaped = $escaped -replace '"', '&quot;'
            $escaped = $escaped -replace "'", '&apos;'
            [void]$sb.AppendLine('    <string name="' + $name + '">' + $escaped + '</string>')
        }
        [void]$sb.AppendLine('</resources>')
        Set-Content -Path $path -Value $sb.ToString() -Encoding UTF8
    }
}
Write-Host 'Done'
