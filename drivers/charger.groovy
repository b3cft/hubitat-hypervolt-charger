metadata {
  definition(
    name: 'HyperVolt Charger',
    namespace: 'b3cft',
    author: "Andy 'Bob' Brockhurst",
    importUrl: 'https://raw.githubusercontent.com/b3cft/hubitat-hypervolt-charger/main/drivers/charger.groovy'
  ) {
    capability 'Initialize'
    capability 'Sensor'

    capability 'Lock'
    attribute 'lock', 'string'

    attribute 'charger_state', 'enum', ['ready', 'stopped', 'unknown']

    attribute 'current_limit', 'number'

    /*
        sync attributes
    */
    attribute 'brightness', 'number'
    attribute 'effect_name', 'string'
    attribute 'solar_mode', 'enum', ['boost', 'eco', 'super_eco']
    attribute 'max_current', 'number'
    attribute 'random_start', 'boolean'
  }
  preferences {
    input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true // normally false
  }
  preferences {
    input name: 'logInfo', type: 'bool', title: 'Enable info logging', defaultValue: true
  }

  command 'connect'
  command 'disconnect'
  command 'refresh'

  command 'test'

  command 'lock'
  command 'unlock'

  command 'release'
  command 'releaseReset'

  command 'setSolarMode', [
    [name: 'Solar Mode',
     type: 'ENUM',
     constraints: ['boost', 'eco', 'super_eco'],
     description: 'Select Solar Mode to use']
  ]

  command 'setCurrentLimit', [
    [name: 'Current Limit (Amps)', type: 'NUMBER', description: 'Limit the maximum current supplied to EV']
  ]
}

/***************************************************************\
 HyperVolt Commands
\***************************************************************/

void refresh() {
  logDebug('refresh()')
  sendMessage('{"id":"' + now() + '","method":"sync.snapshot"}')
}

void test() {
  logDebug getParent().loggedIn
// parse('{"jsonrpc":"2.0","method":"sync.snapshot","params":[{"brightness":1.0},' +
//       '{"effect_name":"none"},{"solar_mode":"eco"},{"max_current":32000},' +
//       '{"lock_state":"unlocked"},{"release_state":"default"},{"random_start":false},{"random_start":false}]}')
}

void lock() {
  logDebug 'lock()'
  sendMessage('{"id":"' + now() + '","method":"sync.apply","params":{"is_locked":true}}')
}

void unlock() {
  logDebug 'unlock()'
  sendMessage('{"id":"' + now() + '","method":"sync.apply","params":{"is_locked":false}}')
}

void setSolarMode(String mode) {
  logDebug "setSolarMode(${mode})"
  sendMessage('{"id":"' + now() + '","method":"sync.apply","params":{"solar_mode":"' + mode + '"}}')
}

void setCurrentLimit(Number amps) {
  logDebug "setCurrentLimit(${amps})"
  Number trueAmps
  if (amps < 6) {
    trueAmps = 6
  } else if (amps > 32) {
    trueAmps = 32
  } else {
    trueAmps = amps
  }
  milliamps = Math.round(trueAmps) * 1000
  logInfo "Setting current limit to ${milliamps}mA"
  sendMessage('{"id":"' + now() + '","method":"sync.apply","params":{"max_current":"' + milliamps + '"}}')
}

void release() {
  logDebug 'release()'
  logInfo 'Charger released, will not charge until Release Reset is called'
  sendMessage('{"id":"' + now() + '","method":"sync.apply","params":{"release":true}}')
}

void releaseReset() {
  logDebug 'releaseReset()'
  logInfo 'Charger reset, will now charge when pluged in.'
  sendMessage('{"id":"' + now() + '","method":"sync.apply","params":{"release":false}}')
}

/***************************************************************\
 Message Parsing functions
\***************************************************************/

void parse(String msg) {
  logDebug "parse: ${msg}"
  json = new org.json.JSONObject(msg)

  if (json.has('method')) {
    switch (json.method) {
            case ('sync.snapshot'):
            case ('sync.apply'):
        parseParams(json.params)
        break
            default:
                log.warn "Unhandled method ${json.method}"
    }
    } else if (json.has('result')) {
    parseParams(json.result)
    } else {
    log.warn 'Unknown message parsing'
  }
}

void parseParams(params) {
  logDebug "parse params for ${params}"
  params.each { item ->
    name = item.names()[0]
    value = item.get(name)
    sendname = name
    sendvalue = value
    switch (name) {
      case('lock_state'):
        sendname = 'lock'
        sendvalue = parseLockValue(value)
        break

      case('release_state'):
        sendname = 'charger_state'
        if (value == 'default') {
          sendvalue = 'ready'
                } else if (value == 'released') {
          sendvalue = 'stopped'
                } else {
          sendvalue = 'unknown'
        }
        break
    }
    logDebug "sending ${sendname} -> ${sendvalue}"
    sendEvent( name: sendname, value: sendvalue)
  }
}

String parseLockValue(String value) {
  String sendValue = 'unknown'
  switch (value) {
    case('locked'):
      sendValue = 'locked'
      break
    case('pending_lock'):
      sendValue = 'unlocked with timeout'
      break
    case('unlocked'):
      sendValue = 'unlocked'
      break
  }
  return sendValue
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
  return "wss://api.hypervolt.co.uk/ws/charger/${state.chargerId}/sync"
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
