metadata {
  definition(
    name: 'HyperVolt Monitor',
    namespace: 'b3cft',
    author: "Andy 'Bob' Brockhurst",
    importUrl: 'https://raw.githubusercontent.com/b3cft/hubitat-hypervolt-charger/main/drivers/monitor.groovy'
  ) {
    capability 'Initialize'
    capability 'Sensor'

    capability 'Energy Meter'
    attribute 'energy', 'number'

    capability 'CurrentMeter'
    attribute 'amperage', 'number'

    /*
        in-progress attributes
    */
    attribute 'charging', 'boolean'
    attribute 'session', 'boolean'
    attribute 'milli_amps', 'number'
    attribute 'true_milli_amps', 'number'
    attribute 'watt_hours', 'number'
    attribute 'ccy_spent', 'number'
    attribute 'carbon_saved_grams', 'number'
    attribute 'ct_current', 'number'
    attribute 'ct_power', 'number'
    attribute 'voltage', 'number'
  }
  preferences {
    input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true // normally false
    input name: 'logInfo', type: 'bool', title: 'Enable info logging', defaultValue: true
  }

  command 'connect'
  command 'disconnect'
  command 'test'
}

/***************************************************************\
 HyperVolt Commands
\***************************************************************/

void test() {
  parse('{"charging":false,"session":0,"milli_amps":0,"true_milli_amps":66,' +
        '"watt_hours":6403,"ccy_spent":0,"carbon_saved_grams":2798,"ct_current":0,"ct_power":0,"voltage":0}')
}

/***************************************************************\
 Message Parsing functions
\***************************************************************/

void parse(String msg) {
  logDebug "parse: ${msg}"
  json = new org.json.JSONObject(msg)
  json.keys().each { item ->
    sendEvent( name: item, value: json.get(item))
  }
}

/***************************************************************\
 Setup etc
\***************************************************************/

void installed() {
  logDebug 'installed()'
  state.retries = 0
  connect()
}

void uninstalled() {
  logDebug 'uninstalled()'
  disconnect()
}

void initialize() {
  logDebug 'initialize()'
  state.retries = 0
  connect()
}

void setChargerId(String chargerId) {
  state.chargerId = chargerId
}

String getWebsocketUrl() {
  return "wss://api.hypervolt.co.uk/ws/charger/${state.chargerId}/session/in-progress"
}

/***************************************************************\
 Standard WebSockety things
\***************************************************************/

void connect() {
  logDebug 'connect()'
  state.disconnected = false
  try {
    interfaces.webSocket.connect(
      getWebsocketUrl(),
      pingInterval: 30,
      headers: getParent().getWsHeaders()
    )
    state.loggedIn = false
    logDebug 'Websocket connecting'
  }
  catch (e) {
    logDebug "initialize error: ${e.message}"
    log.error 'WebSocket connect failed'
  }
}

void disconnect() {
  logDebug 'websocket closed'
  interfaces.webSocket.close()
  state.loggedIn = false
  state.disconnected = true
}

void sendMessage(String msg) {
  logDebug("Sending: ${msg}")
  interfaces.webSocket.sendMessage(msg)
}

void wsLogin() {
  logDebug 'wsLogin()'
  sendMessage('{"method":"login","params":{"token":"' + getParent().getSession() + '","version":1}}')
  state.loggedIn = true
  logInfo 'Logged in'
}

void webSocketStatus(String msg) {
  logDebug "webSocketStatus(${msg})"
  switch (msg.trim()) {
    case ('status: open'):
      logInfo 'Websocket Connected'
      state.connected = true
      wsLogin()
      break
    default:
      state.connected = false
      state.loggedIn = false
      log.warn "unhandled websocket message: ${msg}"
      if (state.disconnected) {
        logDebug "Deliberately disconnected, don't attempt to reconnect"
      } else if (state.retries < 5) {
        state.retries += 1
        logInfo 'Retry connection in 30s'
        runIn(30, 'connect')
      } else {
        log.warn 'Websocket disconnected and our of retries.'
      }
      break
  }
}

/***************************************************************\
 Logging and stuff
\***************************************************************/

void logInfo(String msg) {
  if (logInfo) {
    log.info msg
  }
}

void logDebug(String msg) {
  if (logEnable) {
    log.debug msg
  }
}
