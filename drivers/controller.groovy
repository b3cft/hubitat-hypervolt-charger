import groovy.transform.Field

//************************************************
//*               STATIC VARIABLES               *
//************************************************
@Field static final String devVersion = '0.0.1'
@Field static final String devUpdated = '2023-06-24'
@Field static final String devicePrefix = 'b3cft-hypervolt-'

public static String loginurl() { return 'https://api.hypervolt.co.uk/login-url' }
public static String useragent() { return "Hubitat HyperVolt API Integration v${devVersion}" }

metadata {
  definition(
    name: 'HyperVolt Controller',
    namespace: 'b3cft',
    author: "Andy 'Bob' Brockhurst",
    importUrl: 'https://raw.githubusercontent.com/b3cft/hubitat-hypervolt-charger/main/drivers/controller.groovy'
  ) {
    //capability "Configuration"
    //capability "Polling"
    //capability "Refresh"

    attribute 'session', 'string'

    command 'login'
    command 'getChargers'
  }

  preferences {
    input name: 'username', type: 'text', title: 'Username', description: 'Your HyperVolt User Name', required: true
    input name: 'password',
          type: 'password',
          title: 'Password',
          description: 'Your HyperVolt password',
          required: true
    input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
    input name: 'logInfo', type: 'bool', title: 'Enable info logging', defaultValue: true
  }
}

/***************************************************************\
 Setup etc
\***************************************************************/

void installed() {
  logInfo 'installed()'
}

void updated() {
  logInfo 'updated()'
  login()
}

/***************************************************************\
 Called by children
\***************************************************************/

String getSession() {
  return state.session
}

def getWsHeaders() {
  return [
    'Accept': 'application/json',
    'User-Agent': useragent(),
    'Cookie': getCookies('api'),
    'Authorization': "bearer ${state.session}"
  ]
}

/***************************************************************\
 Setup children
\***************************************************************/

void getChargers() {
  String bearer = "bearer ${state.session}"
  def result = null
  def params = [
    uri: 'https://api.hypervolt.co.uk/charger/by-owner',
    headers: [
      'Accept': 'application/json',
      'User-Agent': useragent(),
      'Cookie': getCookies('api'),
      'Authorization': bearer
    ]
  ]
  try {
    logDebug "Calling ${params}"
    httpGet(params) { resp -> result = resp }
    logDebug "getChargers status: ${result.getStatus()}"
    logDebug "getChargers data: ${result.data}"
    updateChildren(result.data)
    } catch (e) {
    log.warn "Error during getChargers: $e"
  }
}

void uninstalled() {
  getChildDevices().each { item ->
    deleteChildDevice(item.deviceNetworkId)
  }
}

void updateChildren(data) {
  def childDevices = []
  def expectedChildren = []

  getChildDevices().each { item ->
    childDevices << item.deviceNetworkId
  }
  logDebug "children: ${childDevices}"

  data.chargers?.each { item ->
    expectedChildren <<  "${devicePrefix}c-${item['charger_id']}"
    expectedChildren <<  "${devicePrefix}m-${item['charger_id']}"
  }
  logDebug "expectedChildren: ${expectedChildren}"

  def removed = childDevices - expectedChildren
  removed.each { item ->
    logInfo "Removing child device ${item}"
    deleteChildDevice(item)
  }

  data.chargers?.each { item ->
    chargerId = item['charger_id']
    if (!getChildDevice("${devicePrefix}c-${chargerId}")) {
      logInfo "Adding child charger: ${item}"
      child = addChildDevice(
        'HyperVolt Charger',
        "${devicePrefix}c-${chargerId}",
        [
          'name':"charger-${chargerId}",
          'label':"HyperVolt Charger id: ${chargerId}"
        ]
      )
      child.setChargerId(chargerId)
    }
    if (!getChildDevice("${devicePrefix}m-${item['charger_id']}")) {
      logInfo "Adding child Monitor: ${item}"
      child = addChildDevice(
        'HyperVolt Monitor',
        "${devicePrefix}m-${chargerId}",
        [
          'name':"monitor-${chargerId}",
          'label':"HyperVolt Monitor id: ${chargerId}"
        ]
      )
      child.setChargerId(chargerId)
    }
  }
}

/***************************************************************\
 Bastard login flow. FML
\***************************************************************/

def getLoginUrl() {
  clearCookies('api')
  def result = null
  def params = [
    uri: loginurl(),
    headers: [
      'Accept': 'application/json',
      'User-Agent': useragent()
    ]
  ]
  try {
    logDebug "Calling ${params}"
    httpGet(params) { resp -> result = resp }
    logDebug "getLoginUrl returned: ${result.data}"
    logDebug "getLoginUrl returning URL: ${result.data.login}"
    storeCookies('api', result.getHeaders('Set-Cookie'))
    return result.data.login
  } catch (e) {
    log.warn "Error during getLoginUrl: $e"
  }
}

void login() {
  clearCookies('auth')

    /*
        Fetch the login url
        We get a redirect to another url, but we need the cookies first
    */
  def result = null
  def params = [
    uri: getLoginUrl(),
    followRedirects: false,
    headers: [
      'User-Agent': useragent()
    ]
  ]
  logDebug "login fetching ${params}"
  httpGet(params) { resp -> result = resp }

  // This is the redirect form location
  String redirectLocation = result.getFirstHeader('location').getValue()
  logDebug "Login form location is: ${redirectLocation}"

  // It's a relative redirect so we need the host
  String loginHost = params.uri.split('/')[2]
  logDebug "loginHost: ${loginHost}"

  // We also need the state querysting parameter in the redirect
  String loginState = redirectLocation.split('state=')[1]
  logDebug "loginState: ${loginState}"

  // Stash the cookies in the redirect response
  cookies = storeCookies('auth', result.getHeaders('Set-Cookie'))

    /*
        Now we post our creds to the login form with cookies
        We get updated cookies, a new state value and a redirect
    */
  result = null
  params = [
    uri: "https://${loginHost}${redirectLocation}",
    headers: [
      'Content-Type': 'application/x-www-form-urlencoded',
      'User-Agent': useragent(),
      'Cookie': cookies
    ],
    body: [state: loginState, username: "${settings.username}", password: "${settings.password}", action: 'default']
  ]

  logDebug "login posting ${params}"
  httpPost(params) { resp -> result = resp }

  // Stash the cookies sent with the redirect response
  clearCookies('auth')
  cookies = storeCookies('auth', result.getHeaders('Set-Cookie'))

  redirectLocation = result.getFirstHeader('location').getValue()
  logDebug "Auth redirect location is: ${redirectLocation}"

    // We may also need the state querystring parameter in the redirect
    //loginState = redirectLocation.split('state=')[1]
    //log.debug "New loginState: ${loginState}"

    /*
        Follow the next redirect to validate the auth
    */
  result = null
  params = [
    uri: "https://${loginHost}${redirectLocation}",
    followRedirects: false,
    headers: [
      'User-Agent': useragent(),
      'Cookie': cookies
    ]
  ]
  logDebug "login fetching ${params}"
  httpGet(params) { resp -> result = resp }

  redirectLocation = result.getFirstHeader('location').getValue()

    /*
        Follow redirect back to the API host
    */
  result = null
  params = [
    uri: redirectLocation,
    followRedirects: false,
    headers: [
      'User-Agent': useragent(),
      'Cookie': getCookies('api')
    ]
  ]
  logDebug "login fetching ${params}"
  httpGet(params) { resp -> result = resp }

  // look for a session cookie as store it for later
  result.getHeaders('Set-Cookie').each { item ->
    String cookie = item.value.split(';|,')[0]
    cookieBits = cookie.split('=')
    if (cookieBits[0] == 'session') {
      device.data.session = cookieBits[1]
      state.session = cookieBits[1]
      logDebug "Session found: ${device.data.session}"
    }
  }

  // store the rest of the cookies
  cookies = storeCookies('api', result.getHeaders('Set-Cookie'))

  // Final redirect?
  redirectLocation = result.getFirstHeader('location').getValue()

    /*
        Follow redirect back on the API host
    */
  result = null
  params = [
    uri: redirectLocation,
    followRedirects: false,
    headers: [
      'User-Agent': useragent(),
      'Cookie': cookies
    ]
  ]
  logDebug "login fetching ${params}"
  httpGet(params) { resp -> result = resp }

  if (logEnable) {
    logDebug "API Request: ${result.getStatus()}"
    logDebug "API Request: ${result.data}"
    result.getHeaders().each { item ->
      logDebug "header: ${item}"
    }
  }

    /*
        Finally
    */
  logInfo 'Logged In'
  getChargers()
}

/***************************************************************\
 Cookies
\***************************************************************/

String storeCookies(String site, cookies) {
  cookies.each { item ->
    String cookie = item.value.split(';|,')[0]
    state.cookies[site] += cookie + ';'
    logDebug "Adding cookie ${cookie}"
  }
  logDebug "Device ${site} cookies: ${state.cookies[site]}"
  return state.cookies[site]
}

String getCookies(String site) {
  return state.cookies[site]
}

void clearCookies(String site) {
  try {
    state.cookies[site] = ''
    } catch (e) {
    state.cookies = [:]
    state.cookies[site] = ''
  }
}

/***************************************************************\
 Logging and stuff
\***************************************************************/

void logDebug(String msg) {
  if (logEnable) {
    log.debug msg
  }
}
void logInfo(String msg) {
  if (logInfo) {
    log.info msg
  }
}
