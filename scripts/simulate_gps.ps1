$url = "http://localhost:5055"
$id = "dummy123"
$lat = 51.5074
$lon = -0.1278
$speed = 10 # Km/h
$bearing = 45 # Degrees

while ($true) {
    # Update position (naive calculation for demonstration)
    $lat += (0.0001 * [Math]::Cos($bearing * [Math]::PI / 180))
    $lon += (0.0001 * [Math]::Sin($bearing * [Math]::PI / 180))
    
    $query = "?id=$id&lat=$lat&lon=$lon&speed=$speed&bearing=$bearing"
    $fullUrl = $url + $query
    
    Write-Host "Sending update: $fullUrl"
    try {
        Invoke-WebRequest -Uri $fullUrl -Method Get -UseBasicParsing | Out-Null
    } catch {
        Write-Error "Failed to send update: $_"
    }
    
    Start-Sleep -Seconds 5
}
