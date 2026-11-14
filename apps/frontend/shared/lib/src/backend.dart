import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

typedef ApiData = Map<String, dynamic>;

class ServiceFailure implements Exception {
  const ServiceFailure(this.status, this.message);
  final int status;
  final String message;
  @override String toString() => message;
}

/// Replace this adapter when the team's final REST routes/DTOs are agreed.
/// Authentication, resource authorization and dispatch ranking belong to server.
abstract interface class FrontendBackend {
  Future<ApiData> call(String operation, [ApiData data = const {}]);
  void clearSession();
}

class HttpFrontendBackend implements FrontendBackend {
  HttpFrontendBackend(this.baseUrl, {http.Client? client}) : _client = client ?? http.Client() {
    final uri = Uri.parse(baseUrl);
    if (uri.host.isEmpty || uri.userInfo.isNotEmpty || uri.hasQuery || uri.hasFragment ||
        (uri.scheme != 'https' && !(kDebugMode && uri.scheme == 'http' &&
          ['localhost', '127.0.0.1', '10.0.2.2'].contains(uri.host)))) {
      throw ArgumentError('API_BASE_URL must use HTTPS (debug loopback HTTP allowed).');
    }
  }
  final String baseUrl;
  final http.Client _client;
  String? _accessToken;
  String? _refreshToken;
  Future<ApiData>? _refreshing;
  int _generation = 0;
  // Tokens are memory-only. Restart requires authentication; no credentials or
  // TOTP secret is written to SharedPreferences or to application logs.
  @override void clearSession() { _accessToken = null; _refreshToken = null; _generation++; }
  void close() { clearSession(); _client.close(); }

  @override Future<ApiData> call(String operation, [ApiData data = const {}]) => _request(operation,data);
  Future<ApiData> _request(String operation, ApiData data, {bool allowRefresh=true}) async {
    if (!RegExp(r'^[a-z][a-z0-9-]*(/[a-z][a-z0-9-]*)*$').hasMatch(operation)) {
      throw ArgumentError('Invalid API operation');
    }
    final generation = _generation;
    try {
      // An explicit operation API avoids sending writable client state or
      // trusting locally calculated approval/dispatch results on the server.
      final request = http.Request('POST', Uri.parse('${baseUrl.replaceFirst(RegExp(r'/+$'), '')}/$operation'))
        ..followRedirects = false
        ..headers.addAll({'Content-Type': 'application/json',
          if (_accessToken != null) 'Authorization': 'Bearer $_accessToken'})
        ..body = jsonEncode(data);
      final response = await http.Response.fromStream(await _client.send(request)
        .timeout(const Duration(seconds: 15))).timeout(const Duration(seconds: 15));
      if (generation != _generation) throw const ServiceFailure(499, '요청이 취소되었습니다.');
      if (response.statusCode < 200 || response.statusCode >= 300) {
        if(response.statusCode==401&&allowRefresh&&_refreshToken!=null&&!operation.startsWith('auth/')){
          try{
            _refreshing??=_request('auth/refresh',{'refreshToken':_refreshToken},allowRefresh:false);
            await _refreshing;
          }finally{_refreshing=null;}
          return await _request(operation,data,allowRefresh:false);
        }
        if (response.statusCode == 401) clearSession();
        throw ServiceFailure(response.statusCode, switch(response.statusCode) {
          401 => '인증이 만료되었습니다. 다시 로그인해 주세요.',
          403 => '이 작업에 접근할 권한이 없습니다.',
          404 || 410 => '요청이 삭제되었거나 만료되었습니다.',
          409 => '다른 사용자가 먼저 처리했습니다. 최신 상태를 확인해 주세요.',
          429 => '요청이 많습니다. 잠시 후 다시 시도해 주세요.',
          _ => '요청을 처리하지 못했습니다. 다시 시도해 주세요.',
        });
      }
      if (response.body.isEmpty) return {};
      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      if (decoded is! ApiData) throw const FormatException();
      if (['auth/admin-login', 'auth/kakao', 'auth/refresh'].contains(operation)) {
        final token = decoded['accessToken'];
        if (token is! String || token.isEmpty) throw const FormatException();
        if(operation!='auth/refresh'){_refreshToken=null;_generation++;}
        _accessToken = token;
        final refresh=decoded['refreshToken'];
        if(refresh is String&&refresh.isNotEmpty)_refreshToken=refresh;
      }
      return decoded;
    } on ServiceFailure { rethrow; }
    on TimeoutException { throw const ServiceFailure(0, '응답 시간이 초과되었습니다. 연결 상태를 확인해 주세요.'); }
    catch (_) { throw const ServiceFailure(0, '서버에 연결하지 못했습니다. 연결 상태를 확인해 주세요.'); }
  }
}
