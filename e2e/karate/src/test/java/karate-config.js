function fn() {
  var env = karate.env || 'local';

  var config = {
    baseUrl: karate.properties['baseUrl'] || 'http://localhost:8080',
    managementBaseUrl: karate.properties['managementBaseUrl'] || 'http://localhost:9091',
    jaegerBaseUrl: env === 'docker' ? 'http://jaeger:16686' : 'http://localhost:16686',
    grafanaBaseUrl: env === 'docker' ? 'http://grafana:3000' : 'http://localhost:3000',
    tenantSlug: 'dev-coc',
    adminEmail: 'admin@dev.fabt.org',
    adminPassword: 'admin123',
    cocadminEmail: 'cocadmin@dev.fabt.org',
    cocadminPassword: 'admin123',
    outreachEmail: 'outreach@dev.fabt.org',
    outreachPassword: 'admin123'
  };

  // Login URL for auth helper feature
  config.loginUrl = config.baseUrl + '/api/v1/auth/login';

  // Helper: login and return token — uses karate.call() (not callSingle) because
  // JWT tokens expire and different features may need fresh tokens.
  config.login = function(email, password) {
    var result = karate.call('classpath:common/auth.feature', {
      loginUrl: config.loginUrl,
      email: email,
      password: password,
      tenantSlug: config.tenantSlug
    });
    return result.accessToken;
  };

  // Pre-authenticate all three roles for convenience.
  // Tokens are stored globally and referenced via configure headers in features.
  config.adminToken = config.login(config.adminEmail, config.adminPassword);
  config.cocadminToken = config.login(config.cocadminEmail, config.cocadminPassword);
  config.outreachToken = config.login(config.outreachEmail, config.outreachPassword);

  // Default auth header — features can override per-scenario with * header Authorization
  config.adminAuthHeader = 'Bearer ' + config.adminToken;
  config.cocadminAuthHeader = 'Bearer ' + config.cocadminToken;
  config.outreachAuthHeader = 'Bearer ' + config.outreachToken;

  return config;
}
