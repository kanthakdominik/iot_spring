#!/usr/bin/env groovy
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

System.setProperty('file.encoding', 'UTF-8')
System.out = new PrintStream(System.out, true, 'UTF-8')
System.err = new PrintStream(System.err, true, 'UTF-8')

if (args.length < 2) {
    System.err.println 'Użycie: groovy routes_spatial_compare.groovy <trasa_1.csv> <trasa_2.csv> [sourceLat] [sourceLon] [threshold] [peakFactor]'
    System.exit(1)
}

def file1 = new File(args[0])
def file2 = new File(args[1])
[file1, file2].each {
    if (!it.exists()) {
        System.err.println "Nie znaleziono pliku: ${it.path}"
        System.exit(1)
    }
}

// Automatyczne etykiety z nazw plików
def label1 = file1.name
def matcher1 = label1 =~ /_(\d+)\.csv$/
if (matcher1.find()) { label1 = matcher1.group(1) + " cm" }

def label2 = file2.name
def matcher2 = label2 =~ /_(\d+)\.csv$/
if (matcher2.find()) { label2 = matcher2.group(1) + " cm" }

// Parametry źródła
double trueLat = args.length >= 3 ? args[2].replace(',', '.') as Double : 52.218280627634556d
double trueLon = args.length >= 4 ? args[3].replace(',', '.') as Double : 21.010925227559188d

// Threshold przyjmowany dla zgodności (nieużywany w tym skrypcie)
double localRiseThreshold = args.length >= 5 ? args[4].replace(',', '.') as Double : 0.15d

// Peak factor
double peakFactor = args.length >= 6 ? args[5].replace(',', '.') as Double : 0.80d
if (peakFactor <= 0d || peakFactor > 1d) {
    System.err.println 'peakFactor musi należeć do przedziału (0, 1].'
    System.exit(1)
}

double earthRadius = 6371000.0d

def parseCsvLine = { String line ->
    def result = []
    def current = new StringBuilder()
    boolean inQuotes = false
    for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i)
        if (c == '"') {
            inQuotes = !inQuotes
        } else if (c == ',' && !inQuotes) {
            result << current.toString()
            current.setLength(0)
        } else {
            current.append(c)
        }
    }
    result << current.toString()
    result.collect { it.trim().replace('"', '') }
}

def haversine = { double lat1, double lon1, double lat2, double lon2 ->
    double dLat = Math.toRadians(lat2 - lat1)
    double dLon = Math.toRadians(lon2 - lon1)
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    earthRadius * c
}

def plNum = { Number value, int scale = 2 ->
    if (value == null || (value instanceof Double && value.isNaN())) return ''
    String.format(Locale.US, "% .${scale}f", value).trim().replace('.', ',')
}
def plDate = { LocalDateTime dt -> dt ? dt.format(DateTimeFormatter.ofPattern('dd.MM.yyyy HH:mm:ss')) : '' }

def loadRows = { File file ->
    def rows = []
    file.withReader('UTF-8') { reader ->
        def headerLine = reader.readLine()
        if (!headerLine) return
        def header = parseCsvLine(headerLine)
        def idx = [
                latitude : header.indexOf('latitude'),
                longitude: header.indexOf('longitude'),
                timestamp: header.indexOf('timestamp'),
                cpm      : header.indexOf('cpm'),
                usv      : header.indexOf('usv_per_hr')
        ]
        reader.eachLine { line ->
            if (!line?.trim()) return
            def cols = parseCsvLine(line)
            try {
                rows << [
                        latitude : cols[idx.latitude] as BigDecimal,
                        longitude: cols[idx.longitude] as BigDecimal,
                        timestamp: idx.timestamp >= 0 ? LocalDateTime.parse(cols[idx.timestamp], DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss')) : null,
                        cpm      : idx.cpm >= 0 && cols[idx.cpm] ? cols[idx.cpm] as Integer : null,
                        usv      : idx.usv >= 0 && cols[idx.usv] ? cols[idx.usv] as BigDecimal : null
                ]
            } catch (Exception ignored) {
            }
        }
    }
    rows.sort { a, b -> a.timestamp <=> b.timestamp }
}

def signedPathDistance = { List rows, int idxA, int idxB ->
    if (idxA < 0 || idxB < 0 || idxA == idxB) return 0d
    int from = Math.min(idxA, idxB)
    int to = Math.max(idxA, idxB)
    double sum = 0d
    for (int i = from + 1; i <= to; i++) {
        sum += haversine(rows[i - 1].latitude as double, rows[i - 1].longitude as double,
                rows[i].latitude as double, rows[i].longitude as double)
    }
    idxB >= idxA ? sum : -sum
}

def analyze = { File file, String label ->
    def rows = loadRows(file)
    if (!rows) return [label: label, empty: true]

    rows.eachWithIndex { row, idx ->
        row.index = idx
        row.distToSource = haversine(row.latitude as double, row.longitude as double, trueLat, trueLon)
    }

    def validCpmRows = rows.findAll { it.cpm != null }
    def validUsvRows = rows.findAll { it.usv != null }
    def maxCpmRow = validCpmRows.max { it.cpm as int }
    def maxUsvRow = validUsvRows.max { it.usv as double }
    def closestRow = rows.min { it.distToSource as double }

    double maxCpm = maxCpmRow ? maxCpmRow.cpm as double : Double.NaN
    double peakThreshold = maxCpmRow ? maxCpm * peakFactor : Double.NaN
    def peakRows = validCpmRows.findAll { (it.cpm as double) >= peakThreshold }
    if (!peakRows) peakRows = validCpmRows

    double meanLat = peakRows.collect { it.latitude as double }.sum() / peakRows.size()
    double meanLon = peakRows.collect { it.longitude as double }.sum() / peakRows.size()
    double meanCenterError = haversine(meanLat, meanLon, trueLat, trueLon)

    double weightedSum = peakRows.collect { it.cpm as double }.sum()
    double weightedLat = peakRows.collect { (it.latitude as double) * (it.cpm as double) }.sum() / weightedSum
    double weightedLon = peakRows.collect { (it.longitude as double) * (it.cpm as double) }.sum() / weightedSum
    double weightedCenterError = haversine(weightedLat, weightedLon, trueLat, trueLon)

    def peakDistances = peakRows.collect { it.distToSource as double }
    double maxCpmDistance = maxCpmRow ? maxCpmRow.distToSource as double : Double.NaN
    double maxUsvDistance = maxUsvRow ? haversine(maxUsvRow.latitude as double, maxUsvRow.longitude as double, trueLat, trueLon) : Double.NaN

    double signedShiftMeters = maxCpmRow ? signedPathDistance(rows, closestRow.index as int, maxCpmRow.index as int) : Double.NaN
    long signedShiftSeconds = (maxCpmRow && closestRow.timestamp && maxCpmRow.timestamp) ? Duration.between(closestRow.timestamp, maxCpmRow.timestamp).seconds : 0L

    [
            label              : label,
            empty              : false,
            samples            : rows.size(),
            maxCpm             : maxCpm,
            peakThreshold      : peakThreshold,
            peakRowsCount      : peakRows.size(),
            peakShare          : rows ? peakRows.size() * 100.0d / rows.size() : Double.NaN,
            maxCpmTime         : maxCpmRow?.timestamp,
            maxCpmPos          : maxCpmRow ? [lat: maxCpmRow.latitude as double, lon: maxCpmRow.longitude as double] : null,
            maxUsv             : maxUsvRow ? maxUsvRow.usv as double : Double.NaN,
            maxUsvTime         : maxUsvRow?.timestamp,
            maxUsvPos          : maxUsvRow ? [lat: maxUsvRow.latitude as double, lon: maxUsvRow.longitude as double] : null,
            closestTime        : closestRow?.timestamp,
            closestPos         : [lat: closestRow.latitude as double, lon: closestRow.longitude as double],
            closestDistance    : closestRow.distToSource as double,
            meanCenter         : [lat: meanLat, lon: meanLon],
            meanCenterError    : meanCenterError,
            weightedCenter     : [lat: weightedLat, lon: weightedLon],
            weightedCenterError: weightedCenterError,
            peakAvgDistance    : peakDistances.sum() / peakDistances.size(),
            peakMinDistance    : peakDistances.min(),
            peakMaxDistance    : peakDistances.max(),
            maxCpmDistance     : maxCpmDistance,
            maxUsvDistance     : maxUsvDistance,
            shiftMeters        : signedShiftMeters,
            shiftSeconds       : signedShiftSeconds
    ]
}

def a = analyze(file1, label1)
def b = analyze(file2, label2)

println '# Porównanie przestrzenne tras względem rzeczywistego źródła'
println ''
println "**Prawdziwe współrzędne źródła:** `${trueLat}`, `${trueLon}`"
println ''
[a, b].each { r ->
    if (r.empty) {
        println "## ${r.label}"
        println ''
        println 'Brak danych.'
        println ''
        return
    }
    println "## ${r.label}"
    println ''
    println "- **Maksymalny CPM:** ${plNum(r.maxCpm, 0)}"
    println "- **Próg strefy piku (${plNum(peakFactor * 100, 0)}% max CPM):** ${plNum(r.peakThreshold, 2)}"
    println "- **Liczba próbek w strefie piku:** ${r.peakRowsCount}"
    println "- **Punkt najbliższy rzeczywistemu źródłu:** `${plNum(r.closestPos.lat, 6)}`, `${plNum(r.closestPos.lon, 6)}`"
    println "- **Odległość punktu najbliższego od źródła:** ${plNum(r.closestDistance, 2)} m"
    println "- **Środek geometryczny strefy piku:** `${plNum(r.meanCenter.lat, 6)}`, `${plNum(r.meanCenter.lon, 6)}`"
    println "- **Błąd środka geometrycznego:** ${plNum(r.meanCenterError, 2)} m"
    println "- **Środek ważony CPM:** `${plNum(r.weightedCenter.lat, 6)}`, `${plNum(r.weightedCenter.lon, 6)}`"
    println "- **Błąd środka ważonego CPM:** ${plNum(r.weightedCenterError, 2)} m"
    println "- **Pozycja maksimum CPM:** `${plNum(r.maxCpmPos.lat, 6)}`, `${plNum(r.maxCpmPos.lon, 6)}`"
    println "- **Odległość maksimum CPM od źródła:** ${plNum(r.maxCpmDistance, 2)} m"
    println "- **Przesunięcie maksimum CPM względem punktu najbliższego źródłu:** ${plNum(r.shiftMeters, 2)} m, ${r.shiftSeconds} s"
    println ''
}
println '## Tabela do pracy'
println ''
println '| Metryka przestrzenna | ' + a.label + ' | ' + b.label + ' |'
println '|---|---|---|'
println "| Maksymalny CPM | ${plNum(a.maxCpm, 0)} | ${plNum(b.maxCpm, 0)} |"
println "| Próg strefy piku [CPM] | ${plNum(a.peakThreshold, 2)} | ${plNum(b.peakThreshold, 2)} |"
println "| Liczba próbek w strefie piku | ${a.peakRowsCount} | ${b.peakRowsCount} |"
println "| Udział strefy piku [%] | ${plNum(a.peakShare, 2)} | ${plNum(b.peakShare, 2)} |"
println "| Punkt najbliższy źródłu - odległość [m] | ${plNum(a.closestDistance, 2)} | ${plNum(b.closestDistance, 2)} |"
println "| Błąd środka geometrycznego [m] | ${plNum(a.meanCenterError, 2)} | ${plNum(b.meanCenterError, 2)} |"
println "| Błąd środka ważonego CPM [m] | ${plNum(a.weightedCenterError, 2)} | ${plNum(b.weightedCenterError, 2)} |"
println "| Średnia odległość próbek strefy od źródła [m] | ${plNum(a.peakAvgDistance, 2)} | ${plNum(b.peakAvgDistance, 2)} |"
println "| Minimalna odległość próbki strefy od źródła [m] | ${plNum(a.peakMinDistance, 2)} | ${plNum(b.peakMinDistance, 2)} |"
println "| Maksymalna odległość próbki strefy od źródła [m] | ${plNum(a.peakMaxDistance, 2)} | ${plNum(b.peakMaxDistance, 2)} |"
println "| Odległość maksimum CPM od źródła [m] | ${plNum(a.maxCpmDistance, 2)} | ${plNum(b.maxCpmDistance, 2)} |"
println "| Przesunięcie maksimum CPM względem punktu najbliższego źródłu [m] | ${plNum(a.shiftMeters, 2)} | ${plNum(b.shiftMeters, 2)} |"
println "| Przesunięcie maksimum CPM względem punktu najbliższego źródłu [s] | ${a.shiftSeconds} | ${b.shiftSeconds} |"
println "| Maksimum µSv/h | ${plNum(a.maxUsv, 3)} | ${plNum(b.maxUsv, 3)} |"
println "| Odległość maksimum µSv/h od źródła [m] | ${plNum(a.maxUsvDistance, 2)} | ${plNum(b.maxUsvDistance, 2)} |"