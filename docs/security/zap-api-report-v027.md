# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 0 |
| Medium | 1 |
| Low | 1 |
| Informational | 2 |




## Insights

| Level | Reason | Site | Description | Statistic |
| --- | --- | --- | --- | --- |
| Low | Warning |  | ZAP warnings logged - see the zap.log file for details | 2    |
| Info | Informational | http://host.docker.internal:8080 | Percentage of responses with status code 2xx | 1 % |
| Info | Informational | http://host.docker.internal:8080 | Percentage of responses with status code 4xx | 98 % |
| Info | Informational | http://host.docker.internal:8080 | Percentage of endpoints with content type application/json | 22 % |
| Info | Informational | http://host.docker.internal:8080 | Percentage of endpoints with method GET | 100 % |
| Info | Informational | http://host.docker.internal:8080 | Count of total endpoints | 18    |




## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| Source Code Disclosure - SQL | Medium | 1 |
| Cross-Origin-Resource-Policy Header Missing or Invalid | Low | 1 |
| A Client Error response code was returned by the server | Informational | 18 |
| Non-Storable Content | Informational | 1 |




## Alert Detail



### [ Source Code Disclosure - SQL ](https://www.zaproxy.org/docs/alerts/10099/)



##### Medium (Medium)

### Description

Application Source Code was disclosed by the web server. - SQL

* URL: http://host.docker.internal:8080/api/v1/api-docs
  * Node Name: `http://host.docker.internal:8080/api/v1/api-docs`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `update endpoint to set `
  * Other Info: ``


Instances: 1

### Solution

Ensure that application Source Code is not available with alternative extensions, and ensure that source code is not present within other files or data deployed to the web server, or served by the web server.

### Reference


* [ https://nhimg.org/twitter-breach ](https://nhimg.org/twitter-breach)


#### CWE Id: [ 540 ](https://cwe.mitre.org/data/definitions/540.html)


#### WASC Id: 13

#### Source ID: 3

### [ Cross-Origin-Resource-Policy Header Missing or Invalid ](https://www.zaproxy.org/docs/alerts/90004/)



##### Low (Medium)

### Description

Cross-Origin-Resource-Policy header is an opt-in header designed to counter side-channels attacks like Spectre. Resource should be specifically set as shareable amongst different origins.

* URL: http://host.docker.internal:8080/api/v1/api-docs
  * Node Name: `http://host.docker.internal:8080/api/v1/api-docs`
  * Method: `GET`
  * Parameter: `Cross-Origin-Resource-Policy`
  * Attack: ``
  * Evidence: ``
  * Other Info: ``


Instances: 1

### Solution

Ensure that the application/web server sets the Cross-Origin-Resource-Policy header appropriately, and that it sets the Cross-Origin-Resource-Policy header to 'same-origin' for all web pages.
'same-site' is considered as less secured and should be avoided.
If resources must be shared, set the header to 'cross-origin'.
If possible, ensure that the end user uses a standards-compliant and modern web browser that supports the Cross-Origin-Resource-Policy header (https://caniuse.com/mdn-http_headers_cross-origin-resource-policy).

### Reference


* [ https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Embedder-Policy ](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Embedder-Policy)


#### CWE Id: [ 693 ](https://cwe.mitre.org/data/definitions/693.html)


#### WASC Id: 14

#### Source ID: 3

### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 401 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: http://host.docker.internal:8080
  * Node Name: `http://host.docker.internal:8080`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://host.docker.internal:8080/
  * Node Name: `http://host.docker.internal:8080/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://host.docker.internal:8080/377944708608471294
  * Node Name: `http://host.docker.internal:8080/377944708608471294`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/actuator/health
  * Node Name: `http://host.docker.internal:8080/actuator/health`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://host.docker.internal:8080/api
  * Node Name: `http://host.docker.internal:8080/api`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/api/
  * Node Name: `http://host.docker.internal:8080/api/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/api/622925092847745489
  * Node Name: `http://host.docker.internal:8080/api/622925092847745489`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/api/v1
  * Node Name: `http://host.docker.internal:8080/api/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/api/v1/
  * Node Name: `http://host.docker.internal:8080/api/v1/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/api/v1/867070906994530870
  * Node Name: `http://host.docker.internal:8080/api/v1/867070906994530870`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/api/v1/api-docs/
  * Node Name: `http://host.docker.internal:8080/api/v1/api-docs/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://host.docker.internal:8080/computeMetadata/v1/
  * Node Name: `http://host.docker.internal:8080/computeMetadata/v1/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/latest/meta-data/
  * Node Name: `http://host.docker.internal:8080/latest/meta-data/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/metadata/instance
  * Node Name: `http://host.docker.internal:8080/metadata/instance`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/metadata/v1
  * Node Name: `http://host.docker.internal:8080/metadata/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/opc/v1/instance/
  * Node Name: `http://host.docker.internal:8080/opc/v1/instance/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/opc/v2/instance/
  * Node Name: `http://host.docker.internal:8080/opc/v2/instance/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``
* URL: http://host.docker.internal:8080/openstack/latest/meta_data.json
  * Node Name: `http://host.docker.internal:8080/openstack/latest/meta_data.json`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `401`
  * Other Info: ``


Instances: 18

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: http://host.docker.internal:8080/api/v1/api-docs
  * Node Name: `http://host.docker.internal:8080/api/v1/api-docs`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``


Instances: 1

### Solution

The content may be marked as storable by ensuring that the following conditions are satisfied:
The request method must be understood by the cache and defined as being cacheable ("GET", "HEAD", and "POST" are currently defined as cacheable)
The response status code must be understood by the cache (one of the 1XX, 2XX, 3XX, 4XX, or 5XX response classes are generally understood)
The "no-store" cache directive must not appear in the request or response header fields
For caching by "shared" caches such as "proxy" caches, the "private" response directive must not appear in the response
For caching by "shared" caches such as "proxy" caches, the "Authorization" header field must not appear in the request, unless the response explicitly allows it (using one of the "must-revalidate", "public", or "s-maxage" Cache-Control response directives)
In addition to the conditions above, at least one of the following conditions must also be satisfied by the response:
It must contain an "Expires" header field
It must contain a "max-age" response directive
For "shared" caches such as "proxy" caches, it must contain a "s-maxage" response directive
It must contain a "Cache Control Extension" that allows it to be cached
It must have a status code that is defined as cacheable by default (200, 203, 204, 206, 300, 301, 404, 405, 410, 414, 501).

### Reference


* [ https://datatracker.ietf.org/doc/html/rfc7234 ](https://datatracker.ietf.org/doc/html/rfc7234)
* [ https://datatracker.ietf.org/doc/html/rfc7231 ](https://datatracker.ietf.org/doc/html/rfc7231)
* [ https://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html ](https://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html)


#### CWE Id: [ 524 ](https://cwe.mitre.org/data/definitions/524.html)


#### WASC Id: 13

#### Source ID: 3


