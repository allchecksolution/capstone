import 'package:flutter/foundation.dart';

/// One server address for authentication, driver and reception APIs.
/// Accept the previous /api/v1 setting so existing development configs work.
class ApiEndpoint {
  ApiEndpoint(String baseUrl) : origin = _parse(baseUrl);

  final Uri origin;

  String get apiBaseUrl => origin.replace(path: '/api/v1').toString();

  static Uri _parse(String baseUrl) {
    final uri = Uri.parse(baseUrl);
    final localDebug =
        kDebugMode &&
        uri.scheme == 'http' &&
        ['localhost', '127.0.0.1', '10.0.2.2'].contains(uri.host);
    if (uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (uri.scheme != 'https' && !localDebug)) {
      throw ArgumentError(
        'API_BASE_URL must use HTTPS (debug loopback HTTP allowed).',
      );
    }
    if (!['', '/', '/api/v1', '/api/v1/'].contains(uri.path)) {
      throw ArgumentError(
        'API_BASE_URL must be the server origin or end with /api/v1.',
      );
    }
    return uri.replace(path: '');
  }
}
