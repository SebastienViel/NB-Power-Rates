/**
 *  NB Power Solar Billing Tracker  v1.1
 *
 *  Monitors Emporia Vue power data to compare the current NB Power net metering
 *  rate structure against the proposed three-part rate (import rate + export credit
 *  + demand charge), pending EUB approval April 1, 2027.
 *
 *  Requires: Emporia Vue devices exposing the PowerMeter capability (watts).
 *            Compatible with amithalp/EmporiaV2 and ke7lvb/Emporia-Vue-Hubitat.
 *
 *  v1.1 changes:
 *    - All device inputs now use capability.powerMeter (watts) only.
 *      No energyMeter (kWh) capability required.
 *    - Removed snapshot/manual accumulation mode choice — always integrates
 *      watts × time internally (one reading per minute = 1/60 kWh per kW).
 *    - Separate "peak power device" input removed; the grid import device
 *      is used for both kWh accumulation and demand tracking.
 *    - Watt values from Emporia are directional:
 *        gridImportDevice  → positive watts = consuming from grid
 *        solarExportDevice → positive watts = sending power to grid
 *      The app treats each as unsigned (absolute value) and accumulates
 *      them independently, so negative readings are safely ignored.
 *
 *  Billing math:
 *    Current net metering : (importKwh − exportKwh) × currentRate
 *    Proposed structure   : (importKwh × importRate)
 *                         − (exportKwh × exportRate)
 *                         + (peakKw    × demandCharge)
 *
 *  Author : generated for Sébastien with Claude (Anthropic), May 2026
 *  License: Apache 2.0
 */

definition(
    name:        "NB Power Solar Billing Tracker",
    namespace:   "nbpower",
    author:      "Sébastien",
    description: "Tracks solar import/export kWh and 15-min peak demand to compare " +
                 "current NB Power net metering against the proposed rate structure.",
    category:    "Energy Management",
    iconUrl:     "",
    iconX2Url:   ""
    importUrl:   "https://raw.githubusercontent.com/SebastienViel/NB-Power-Rates/main/NBPowerSolarBillingTracker%20for%20Hubitat.groovy",

)

// ---------------------------------------------------------------------------
// Preferences / UI
// ---------------------------------------------------------------------------
preferences {
    page(name: "mainPage")
    page(name: "ratesPage")
    page(name: "devicesPage")
    page(name: "notificationsPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "NB Power Solar Billing Tracker", install: true, uninstall: true) {

        section("<b>Setup</b>") {
            href "devicesPage",       title: "📡 Energy Devices",  description: devicesDescription(),  required: true
            href "ratesPage",         title: "💲 Rate Settings",   description: ratesDescription(),    required: true
            href "notificationsPage", title: "🔔 Notifications",   description: notifyDescription()
        }

        section("<b>Billing Period</b>") {
            input "billingDay", "number",
                title: "Billing cycle start day of month (e.g. 1 for the 1st)",
                defaultValue: 1, range: "1..28", required: true
            paragraph "Current billing period: <b>${currentBillingPeriodLabel()}</b>"
        }

        section("<b>Dashboard Summary</b>") {
            input "summaryDevice", "capability.actuator",
                title: "Optional: Virtual device to write summary tile attributes to",
                required: false, multiple: false
            paragraph "If set, attributes <i>nbpImportKwh</i>, <i>nbpExportKwh</i>, " +
                      "<i>nbpPeakKw</i>, <i>nbpCurrentCost</i>, <i>nbpProposedCost</i>, " +
                      "<i>nbpDelta</i>, <i>nbpPeakKwTime</i>, <i>nbpBillingStart</i> " +
                      "are written every minute for use in Hubitat dashboards."
        }

        section("<b>Billing Period Controls</b>") {
            input "resetBtn", "button", title: "🔄 Reset Billing Period Now"
            paragraph "Use this at the start of each new billing cycle to zero all counters. " +
                      "The app also resets automatically on the configured billing day."
            paragraph billingStatusParagraph()
        }

        section("<b>Logging</b>") {
            input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def devicesPage() {
    dynamicPage(name: "devicesPage", title: "Energy Devices", nextPage: "mainPage") {

        section("<b>Select Emporia Vue channels</b>") {
            paragraph """The Emporia Vue integration creates one child device per circuit, 
each exposing a <i>power</i> attribute in watts. Select the two mains channels below.

<b>Important:</b> Emporia reports the mains-to-grid (solar export) channel as a 
positive watt value when power is flowing to the grid. Both channels are treated 
as unsigned here — the app accumulates them independently."""

            input "gridImportDevice", "capability.powerMeter",
                title: "Mains FROM grid — energy consumed from the grid (watts)",
                description: "Your 'mains from grid' or grid import CT channel",
                required: true, multiple: false

            input "solarExportDevice", "capability.powerMeter",
                title: "Mains TO grid — energy sent to the grid / solar export (watts)",
                description: "Your 'mains to grid' or solar export CT channel",
                required: true, multiple: false
        }

        section("<b>How kWh is calculated</b>") {
            paragraph """The app polls both devices every minute. Each reading (in watts) 
is divided by 1000 to get kW, then divided by 60 to convert one minute of power 
into kWh (kW × 1/60 h). These increments are summed into running totals for 
the billing period.

For the peak demand calculation, the last 15 one-minute readings from the 
grid import device are averaged to produce a 15-minute average kW value. 
Only readings taken between the configured demand window hours are considered."""
        }
    }
}

def ratesPage() {
    dynamicPage(name: "ratesPage", title: "Rate Settings", nextPage: "mainPage") {

        section("<b>Current NB Power Net Metering Rate</b>") {
            paragraph "Under the current programme, import and export offset at the same rate (1:1)."
            input "currentNetRate", "decimal",
                title: "Net metering rate (¢/kWh)",
                defaultValue: 14.76, range: "0.01..99.99", required: true
        }

        section("<b>Proposed Rate Structure</b>") {
            paragraph "Pending EUB approval for April 1, 2027. Adjust as NB Power confirms final numbers."
            input "proposedImportRate", "decimal",
                title: "Import rate — energy drawn from grid (¢/kWh)",
                defaultValue: 14.76, range: "0.01..99.99", required: true
            input "proposedExportRate", "decimal",
                title: "Export credit — energy sent to grid (¢/kWh)",
                defaultValue: 6.77, range: "0.01..99.99", required: true
            input "demandChargeRate", "decimal",
                title: "Demand charge rate (\$/kW of peak 15-min demand)",
                defaultValue: 13.00, range: "0.01..99.99", required: true
        }

        section("<b>Demand charge window</b>") {
            paragraph "NB Power's proposal applies the demand charge only between <b>7:00 am and 10:00 pm</b>."
            input "demandWindowStart", "number",
                title: "Window start hour (0–23, default 7 = 7:00 am)",
                defaultValue: 7, range: "0..23", required: true
            input "demandWindowEnd", "number",
                title: "Window end hour (0–23, default 22 = 10:00 pm)",
                defaultValue: 22, range: "0..23", required: true
        }
    }
}

def notificationsPage() {
    dynamicPage(name: "notificationsPage", title: "Notifications", nextPage: "mainPage") {
        section("<b>Demand spike alert</b>") {
            paragraph "Send a notification when the current grid import power exceeds a threshold."
            input "notifyDevices", "capability.notification",
                title: "Notification devices", required: false, multiple: true
            input "spikeThresholdKw", "decimal",
                title: "Alert when grid import exceeds (kW) — set 0 to disable",
                defaultValue: 0, range: "0..100", required: false
            input "spikeAlertCooldownMin", "number",
                title: "Minimum minutes between spike alerts",
                defaultValue: 30, range: "1..1440", required: false
        }

        section("<b>Monthly summary notification</b>") {
            input "sendMonthlySummary", "bool",
                title: "Send billing summary notification at start of each new period",
                defaultValue: true
        }
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
def installed() {
    logDebug "Installed"
    initialize()
}

def updated() {
    logDebug "Updated — re-initialising"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (state.billingStart == null) resetBillingPeriod()

    // Poll every minute for watts readings
    schedule("0 * * * * ?", "everyMinute")

    // Auto-reset at midnight on the billing day each month
    schedule("0 0 0 ${billingDay} * ?", "autoBillingReset")

    logInfo "Initialised. Billing period: ${currentBillingPeriodLabel()}"
    refreshSummary()
}

// ---------------------------------------------------------------------------
// Button handler
// ---------------------------------------------------------------------------
def appButtonHandler(btn) {
    if (btn == "resetBtn") {
        logInfo "Manual billing period reset."
        endOfPeriodSummary()
        resetBillingPeriod()
        refreshSummary()
    }
}

// ---------------------------------------------------------------------------
// Billing period reset
// ---------------------------------------------------------------------------
def autoBillingReset() {
    logInfo "Automatic billing period reset on day ${billingDay}."
    if (sendMonthlySummary) endOfPeriodSummary()
    resetBillingPeriod()
    refreshSummary()
}

def resetBillingPeriod() {
    def now = now()
    state.billingStart    = now
    state.billingStartStr = formatDate(now)

    // kWh running totals (accumulated from watts × time)
    state.importKwh       = 0.0
    state.exportKwh       = 0.0

    // 15-minute demand tracking
    // minuteWatts: ring buffer of the last 15 one-minute import watt readings
    state.minuteWatts     = []
    state.peakKw          = 0.0
    state.peakKwTime      = ""

    // Spike alert cooldown
    state.lastSpikeAlert  = 0

    logInfo "Billing period reset at ${state.billingStartStr}."
}

// ---------------------------------------------------------------------------
// Every-minute handler — heart of the app
// ---------------------------------------------------------------------------
def everyMinute() {
    // Read current watts from each device (treat both as unsigned/positive)
    def importW = Math.max(0.0, safeWatts(gridImportDevice))
    def exportW = Math.max(0.0, safeWatts(solarExportDevice))

    // ── kWh accumulation ──────────────────────────────────────────────────
    // 1 minute at importW watts = importW/1000 kW × (1/60) h
    state.importKwh = ((state.importKwh ?: 0.0) + (importW / 1000.0 / 60.0))
    state.exportKwh = ((state.exportKwh ?: 0.0) + (exportW / 1000.0 / 60.0))

    // ── 15-minute rolling demand window ───────────────────────────────────
    def hr = Calendar.getInstance(location.timeZone).get(Calendar.HOUR_OF_DAY)
    def winStart = (demandWindowStart ?: 7) as int
    def winEnd   = (demandWindowEnd   ?: 22) as int

    if (hr >= winStart && hr < winEnd) {
        List buf = state.minuteWatts ?: []
        buf << importW
        // Keep only the last 15 samples (one per minute = 15-minute window)
        if (buf.size() > 15) buf = buf.drop(buf.size() - 15)
        state.minuteWatts = buf

        if (buf.size() == 15) {
            def avgKw = (buf.sum() / 15.0) / 1000.0
            if (avgKw > (state.peakKw ?: 0.0)) {
                state.peakKw     = avgKw.round(4)
                state.peakKwTime = formatDate(now())
                logDebug "New peak 15-min demand: ${state.peakKw} kW at ${state.peakKwTime}"
            }
        }
    } else {
        // Outside the demand window — clear the buffer so it doesn't straddle the boundary
        state.minuteWatts = []
    }

    // ── Spike alert ───────────────────────────────────────────────────────
    def threshKw = (spikeThresholdKw ?: 0) as double
    if (threshKw > 0 && (importW / 1000.0) > threshKw) {
        def cooldownMs = ((spikeAlertCooldownMin ?: 30) * 60 * 1000) as long
        if ((now() - (state.lastSpikeAlert ?: 0L)) > cooldownMs) {
            state.lastSpikeAlert = now()
            def kw      = (importW / 1000.0).round(2)
            def impact  = (kw * (demandChargeRate ?: 13.0)).round(2)
            sendNotifications(
                "⚡ Demand spike: grid import is ${kw} kW " +
                "(threshold ${threshKw} kW). " +
                "If sustained 15 min, demand charge impact: \$${impact}."
            )
        }
    }

    refreshSummary()
}

// ---------------------------------------------------------------------------
// Core calculations
// ---------------------------------------------------------------------------
double importKwh()   { return (state.importKwh ?: 0.0) as double }
double exportKwh()   { return (state.exportKwh ?: 0.0) as double }
double peakKw()      { return (state.peakKw    ?: 0.0) as double }

double currentCost() {
    // Positive result = amount owed; negative = credit to carry forward
    double netKwh = importKwh() - exportKwh()
    return (netKwh * ((currentNetRate ?: 14.76) / 100.0)).round(2)
}

double proposedCost() {
    double importCost   = importKwh() * ((proposedImportRate ?: 14.76) / 100.0)
    double exportCredit = exportKwh() * ((proposedExportRate ?: 6.77)  / 100.0)
    double demandCost   = peakKw()    *  (demandChargeRate   ?: 13.00)
    return (importCost - exportCredit + demandCost).round(2)
}

double delta() { return (proposedCost() - currentCost()).round(2) }

// ---------------------------------------------------------------------------
// Summary helpers
// ---------------------------------------------------------------------------
def refreshSummary() {
    double imp  = importKwh().round(2)
    double exp  = exportKwh().round(2)
    double peak = peakKw().round(3)
    double cur  = currentCost()
    double prop = proposedCost()
    double delt = delta()
    String sign = delt >= 0 ? "+" : ""

    logDebug "Summary — Import: ${imp} kWh | Export: ${exp} kWh | Peak: ${peak} kW | " +
             "Current: \$${cur} | Proposed: \$${prop} | Δ: ${sign}\$${delt}"

    if (summaryDevice) {
        sendEvent(summaryDevice, [name: "nbpImportKwh",    value: imp,                  unit: "kWh"])
        sendEvent(summaryDevice, [name: "nbpExportKwh",    value: exp,                  unit: "kWh"])
        sendEvent(summaryDevice, [name: "nbpPeakKw",       value: peak,                 unit: "kW"])
        sendEvent(summaryDevice, [name: "nbpCurrentCost",  value: cur,                  unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpProposedCost", value: prop,                 unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpDelta",        value: "${sign}${delt}",     unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpPeakKwTime",   value: state.peakKwTime ?: "—"])
        sendEvent(summaryDevice, [name: "nbpBillingStart", value: state.billingStartStr ?: "—"])
    }
}

def endOfPeriodSummary() {
    double imp  = importKwh().round(2)
    double exp  = exportKwh().round(2)
    double peak = peakKw().round(3)
    double cur  = currentCost()
    double prop = proposedCost()
    double delt = delta()
    String sign = delt >= 0 ? "+" : ""

    double importLineCost  = (imp  * (proposedImportRate ?: 14.76) / 100.0).round(2)
    double exportLineCost  = (exp  * (proposedExportRate ?: 6.77)  / 100.0).round(2)
    double demandLineCost  = (peak * (demandChargeRate   ?: 13.00)).round(2)

    def msg = """\
📊 NB Power Billing Period Summary
Period : ${state.billingStartStr} – ${formatDate(now())}
─────────────────────────────────────
Grid import  : ${imp} kWh
Solar export : ${exp} kWh
Net kWh      : ${(imp - exp).round(2)} kWh
Peak 15-min  : ${peak} kW  (at ${state.peakKwTime ?: "not recorded"})
─────────────────────────────────────
Current net metering  : \$${cur}
Proposed structure    : \$${prop}
  Import  ${imp} kWh × ${proposedImportRate}¢  =  \$${importLineCost}
  Export  ${exp} kWh × ${proposedExportRate}¢  = −\$${exportLineCost}
  Demand  ${peak} kW × \$${demandChargeRate}    =  \$${demandLineCost}
─────────────────────────────────────
Proposed vs. current  : ${sign}\$${delt}  (${delt >= 0 ? "MORE expensive" : "less expensive"} under new model)
Annualised projection : ${sign}\$${(delt * 12).round(0)}/yr"""

    logInfo msg
    sendNotifications(msg)
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------
double safeWatts(dev) {
    try {
        def v = dev?.currentValue("power")
        return (v != null) ? (v as double) : 0.0
    } catch (e) {
        logDebug "safeWatts error for ${dev?.displayName}: ${e.message}"
        return 0.0
    }
}

def sendNotifications(String msg) {
    notifyDevices?.each { it.deviceNotification(msg) }
}

String formatDate(long ts) {
    return new Date(ts).format("yyyy-MM-dd HH:mm", location.timeZone)
}

String currentBillingPeriodLabel() {
    def now  = new Date()
    int day  = now.date
    int yr   = now.year + 1900
    int mo   = now.month   // 0-based
    int bd   = (billingDay ?: 1) as int

    Date start
    if (day >= bd) {
        start = Date.parse("yyyy-M-d", "${yr}-${mo + 1}-${bd}")
    } else {
        def cal = Calendar.getInstance()
        cal.set(yr, mo - 1, bd)
        start = cal.time
    }
    return start.format("MMM d") + " – " + (start + 30).format("MMM d, yyyy")
}

String billingStatusParagraph() {
    double imp  = importKwh().round(2)
    double exp  = exportKwh().round(2)
    double peak = peakKw().round(3)
    double cur  = currentCost()
    double prop = proposedCost()
    double delt = delta()
    String sign = delt >= 0 ? "+" : ""

    return "<b>Period started:</b> ${state.billingStartStr ?: '(not set)'}" +
           "<br><b>Import:</b> ${imp} kWh &nbsp; <b>Export:</b> ${exp} kWh" +
           "<br><b>Peak 15-min demand:</b> ${peak} kW (at ${state.peakKwTime ?: '—'})" +
           "<br><b>Current net metering cost:</b> \$${cur}" +
           "<br><b>Proposed cost:</b> \$${prop} &nbsp; <b>Δ</b> ${sign}\$${delt}"
}

String devicesDescription() {
    if (!gridImportDevice || !solarExportDevice) return "Tap to configure ←"
    return "From grid: ${gridImportDevice.displayName}\nTo grid: ${solarExportDevice.displayName}"
}

String ratesDescription() {
    return "Net: ${currentNetRate}¢ | Import: ${proposedImportRate}¢ | " +
           "Export: ${proposedExportRate}¢ | Demand: \$${demandChargeRate}/kW"
}

String notifyDescription() {
    if (!notifyDevices) return "No notifications configured"
    return "${notifyDevices.size()} device(s) | Spike threshold: ${spikeThresholdKw ?: 'off'} kW"
}

void logDebug(msg) { if (debugLog) log.debug "[NBPSolar] ${msg}" }
void logInfo(msg)  { log.info  "[NBPSolar] ${msg}" }
