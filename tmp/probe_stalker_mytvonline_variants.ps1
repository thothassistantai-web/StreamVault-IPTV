param(
    [Parameter(Mandatory = $true)]
    [string]$Portal,

    [Parameter(Mandatory = $true)]
    [string]$Mac,

    [string]$SampleItemId = '874699',
    [string]$Timezone = 'UTC',
    [string]$Locale = 'en'
)

$ErrorActionPreference = 'Stop'

$base = $Portal.TrimEnd('/')
if ($base.ToLowerInvariant().EndsWith('/c')) {
    $root = $base.Substring(0, $base.Length - 2)
} else {
    $root = $base
}

$loadUrl = "$root/server/load.php"
$referer = "$root/c/"
$normalizedMac = $Mac.ToUpperInvariant()
$serialSeed = $normalizedMac.Replace(':', '')
$serial = $serialSeed.Substring([Math]::Max(0, $serialSeed.Length - 13)).PadLeft(13, '0')

function Get-Sha256Hex([string]$Value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return (($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)) | ForEach-Object { $_.ToString('X2') }) -join '')
    } finally {
        $sha.Dispose()
    }
}

function Encode-QueryValue([string]$Value) {
    return [System.Uri]::EscapeDataString($Value)
}

function New-PortalUrl([hashtable]$Query) {
    $effectiveQuery = $Query.Clone()
    if (-not $effectiveQuery.ContainsKey('mac')) {
        $effectiveQuery['mac'] = $normalizedMac
    }
    $pairs = @()
    foreach ($key in $effectiveQuery.Keys) {
        $pairs += "$(Encode-QueryValue $key)=$(Encode-QueryValue ([string]$effectiveQuery[$key]))"
    }
    return "$($loadUrl.TrimEnd('/'))?$($pairs -join '&')"
}

function Hide-Sensitive([string]$Value) {
    if ($null -eq $Value) { return '' }
    return $Value.Replace($normalizedMac, '<MAC>')
}

function Invoke-PortalJson([string]$Label, [hashtable]$Query, [string]$Token, [hashtable]$Headers) {
    $headers = $Headers.Clone()
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $url = New-PortalUrl $Query
    try {
        $response = Invoke-WebRequest -Uri $url -Headers $headers -Method Get -TimeoutSec 20 -UseBasicParsing
        $text = [string]$response.Content
        if ([string]::IsNullOrWhiteSpace($text)) {
            return [pscustomobject]@{ Ok = $false; Error = "$Label empty body"; Json = $null }
        }
        return [pscustomobject]@{ Ok = $true; Error = ''; Json = ($text | ConvertFrom-Json) }
    } catch {
        return [pscustomobject]@{ Ok = $false; Error = "$Label $($_.Exception.Message)"; Json = $null }
    }
}

function New-Profile([string]$Model, [bool]$HashedIdentity, [int]$AuthSecondStep, [string]$UserAgentSuffix = '') {
    $deviceId = if ($HashedIdentity) { Get-Sha256Hex ('device:{0}:{1}' -f $Model, $normalizedMac) } else { '' }
    $deviceId2 = if ($HashedIdentity) { Get-Sha256Hex ('device2:{0}:{1}' -f $Model, $normalizedMac) } else { '' }
    $signature = if ($HashedIdentity) { Get-Sha256Hex ('signature:{0}:{1}:{2}' -f $Model, $normalizedMac, $Timezone) } else { '' }
    $uaModel = if ($UserAgentSuffix) { $UserAgentSuffix } else { $Model }
    return [pscustomobject]@{
        Name = "$Model auth=$AuthSecondStep identity=$(if ($HashedIdentity) { 'hashed' } else { 'empty' })"
        Model = $Model
        DeviceId = $deviceId
        DeviceId2 = $deviceId2
        Signature = $signature
        AuthSecondStep = $AuthSecondStep
        UserAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) $uaModel stbapp ver: 2 rev: 250 Safari/533.3"
        XUserAgent = "Model: $uaModel; Link: Ethernet"
    }
}

function Test-Media([string]$Url, [string]$Token, [pscustomobject]$Profile, [string]$PlaybackUserAgent, [bool]$SendAuth, [bool]$SendFullCookie) {
    try {
        $request = [System.Net.HttpWebRequest]::Create($Url)
        $request.Method = 'GET'
        $request.Timeout = 15000
        $request.ReadWriteTimeout = 5000
        $request.AllowAutoRedirect = $false
        $request.UserAgent = $PlaybackUserAgent
        $request.Accept = '*/*'
        $request.Referer = $referer
        $request.Headers['X-User-Agent'] = $Profile.XUserAgent
        if ($SendFullCookie) {
            $request.Headers['Cookie'] = "mac=$normalizedMac; stb_lang=$Locale; timezone=$Timezone; sn=$serial; device_id=$($Profile.DeviceId); device_id2=$($Profile.DeviceId2); signature=$($Profile.Signature)"
        } else {
            $request.Headers['Cookie'] = "mac=$normalizedMac; stb_lang=$Locale; timezone=$Timezone"
        }
        if ($SendAuth -and $Token) {
            $request.Headers['Authorization'] = "Bearer $Token"
        }
        $response = $request.GetResponse()
        try {
            $stream = $response.GetResponseStream()
            $buffer = New-Object byte[] 256
            $read = $stream.Read($buffer, 0, $buffer.Length)
            return [pscustomobject]@{ Code = [int]$response.StatusCode; Bytes = $read; ContentType = $response.ContentType }
        } finally {
            $response.Close()
        }
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { -1 }
        return [pscustomobject]@{ Code = $code; Bytes = 0; ContentType = $_.Exception.Message }
    }
}

$profiles = @(
    (New-Profile 'MAG250' $true 1),
    (New-Profile 'MAG250' $true 0),
    (New-Profile 'MAG250' $false 0),
    (New-Profile 'MAG254' $true 1),
    (New-Profile 'MAG322' $true 1),
    (New-Profile 'MAG420' $true 1),
    (New-Profile 'Formuler Z10 Pro' $true 1 'Formuler Z10 Pro'),
    (New-Profile 'Formuler Z11 Pro Max' $true 1 'Formuler Z11 Pro Max')
)

$playbackUaLabels = @(
    @{ Label = 'profile-ua'; Value = $null },
    @{ Label = 'vlc'; Value = 'VLC/3.0.20 LibVLC/3.0.20' },
    @{ Label = 'ffmpeg'; Value = 'Lavf/58.45.100' },
    @{ Label = 'mytvonline-ish'; Value = 'Mozilla/5.0 (Linux; Android 11; Formuler Z10 Pro) MyTVOnline/2.0' }
)

foreach ($profile in $profiles) {
    $commonHeaders = @{
        'User-Agent' = $profile.UserAgent
        'X-User-Agent' = $profile.XUserAgent
        'Referer' = $referer
        'Accept' = '*/*'
        'SN' = $serial
        'Cookie' = "mac=$normalizedMac; stb_lang=$Locale; timezone=$Timezone"
    }

    $handshake = Invoke-PortalJson 'handshake' @{
        type = 'stb'
        action = 'handshake'
        token = ''
        prehash = '0'
        JsHttpRequest = '1-xml'
    } $null $commonHeaders
    if (-not $handshake.Ok) {
        Write-Host "MISS profile='$($profile.Name)' handshake=$($handshake.Error)"
        continue
    }
    $token = [string]$handshake.Json.js.token
    if (-not $token) {
        Write-Host "MISS profile='$($profile.Name)' handshake-no-token"
        continue
    }

    $timestamp = [string][int][double]::Parse((Get-Date -UFormat %s))
    $metricsJson = '{"mac":"' + $normalizedMac + '","sn":"' + $serial + '","model":"' + $profile.Model + '","type":"STB","uid":"' + $profile.DeviceId2 + '","random":"' + [string]$handshake.Json.js.random + '"}'
    $profileResult = Invoke-PortalJson 'get_profile' @{
        type = 'stb'
        action = 'get_profile'
        JsHttpRequest = '1-xml'
        hd = '1'
        ver = 'ImageDescription: 0.2.18-r23-250; ImageDate: Wed Oct 31 15:22:54 EEST 2018; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c'
        num_banks = '2'
        sn = $serial
        stb_type = $profile.Model
        client_type = 'STB'
        image_version = '218'
        video_out = 'hdmi'
        device_id = $profile.DeviceId
        device_id2 = $profile.DeviceId2
        signature = $profile.Signature
        auth_second_step = [string]$profile.AuthSecondStep
        hw_version = '1.7-BD-00'
        not_valid_token = '0'
        metrics = $metricsJson
        hw_version_2 = $profile.Model
        timestamp = $timestamp
        api_signature = '262'
        prehash = '0'
    } $token $commonHeaders
    if (-not $profileResult.Ok) {
        Write-Host "MISS profile='$($profile.Name)' profile=$($profileResult.Error)"
        continue
    }

    $catalog = Invoke-PortalJson 'get_ordered_list' @{
        type = 'itv'
        action = 'get_ordered_list'
        JsHttpRequest = '1-xml'
        force_ch_link_check = '0'
        fav = '0'
        p = '1'
    } $token $commonHeaders
    if (-not $catalog.Ok) {
        Write-Host "MISS profile='$($profile.Name)' catalog=$($catalog.Error)"
        continue
    }
    $item = @($catalog.Json.js.data) | Where-Object { ([string]$_.id) -eq $SampleItemId } | Select-Object -First 1
    if (-not $item) {
        Write-Host "MISS profile='$($profile.Name)' sample-not-found"
        continue
    }

    $link = Invoke-PortalJson 'create_link' @{
        type = 'itv'
        action = 'create_link'
        JsHttpRequest = '1-xml'
        cmd = [string]$item.cmd
        series = '0'
        forced_storage = '0'
        disable_ad = '0'
        download = '0'
    } $token $commonHeaders
    if (-not $link.Ok) {
        Write-Host "MISS profile='$($profile.Name)' create_link=$($link.Error)"
        continue
    }
    $resolvedCmd = [string]$link.Json.js.cmd
    $spaceIndex = $resolvedCmd.IndexOf(' ')
    $resolvedUrl = if ($spaceIndex -ge 0) { $resolvedCmd.Substring($spaceIndex + 1).Trim() } else { $resolvedCmd.Trim() }
    Write-Host "PROFILE '$($profile.Name)' status=$($profileResult.Json.js.status) auth_access=$($profileResult.Json.js.auth_access) link=$(Hide-Sensitive $resolvedUrl)"

    foreach ($ua in $playbackUaLabels) {
        $playbackUserAgent = if ($ua.Value) { $ua.Value } else { $profile.UserAgent }
        foreach ($sendAuth in @($true, $false)) {
            foreach ($fullCookie in @($true, $false)) {
                $media = Test-Media $resolvedUrl $token $profile $playbackUserAgent $sendAuth $fullCookie
                $label = "ua=$($ua.Label) auth=$sendAuth cookie=$(if ($fullCookie) { 'full' } else { 'basic' })"
                Write-Host "  $label -> HTTP $($media.Code) bytes=$($media.Bytes) type=$($media.ContentType)"
                if ($media.Code -ne 204 -and $media.Bytes -gt 0) {
                    Write-Host "  HIT profile='$($profile.Name)' $label"
                    exit 0
                }
            }
        }
    }
}