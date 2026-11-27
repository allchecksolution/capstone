import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'reception_backend.dart';

part 'driver_backend.dart';
part 'admin_backend.dart';

typedef ApiData = Map<String, dynamic>;

class ServiceFailure implements Exception {
  const ServiceFailure(this.status, this.message, {this.code});
  final int status;
  final String message;
  final String? code;
  @override String toString() => message;
}

/// Replace this adapter when the team's final REST routes/DTOs are agreed.
/// Authentication, resource authorization and dispatch ranking belong to server.
abstract interface class FrontendBackend {
  Future<ApiData> call(String operation, [ApiData data = const {}]);
  void clearSession();
}

class HttpFrontendBackend implements FrontendBackend {
  HttpFrontendBackend(this.baseUrl, {http.Client? client, this.restAuth=false}) : _client = client ?? http.Client() {
    final uri = Uri.parse(baseUrl);
    if (uri.host.isEmpty || uri.userInfo.isNotEmpty || uri.hasQuery || uri.hasFragment ||
        (uri.scheme != 'https' && !(kDebugMode && uri.scheme == 'http' &&
          ['localhost', '127.0.0.1', '10.0.2.2'].contains(uri.host)))) {
      throw ArgumentError('API_BASE_URL must use HTTPS (debug loopback HTTP allowed).');
    }
  }
  final String baseUrl;
  final bool restAuth;
  final http.Client _client;
  String? _accessToken;
  String? _refreshToken;
  Future<ApiData>? _refreshing;
  int _generation = 0;
  // Tokens are memory-only. Restart requires authentication; no credentials or
  // TOTP secret is written to SharedPreferences or to application logs.
  @override void clearSession() { _accessToken = null; _refreshToken = null; _generation++; }
  void close() { clearSession(); _client.close(); }

  Future<ApiData> _chat(String operation, String room, ApiData data) {
    if (_accessToken == null) {
      throw const ServiceFailure(401, '서버 인증 연결이 필요합니다.');
    }
    if (operation == 'chat/send') {
      return _request(operation, {'body': data['text']}, path: '$room/messages',
        headers: {'Idempotency-Key': data['requestId'] as String});
    }
    final before = data['before'];
    final query = before == null ? '' : '?before=${Uri.encodeQueryComponent(before.toString())}';
    return _request(operation, const {}, path: '$room/messages$query', method: 'GET');
  }

  @override Future<ApiData> call(String operation, [ApiData data = const {}]) => _request(operation,data);
  Future<ApiData> _request(String operation, ApiData data, {bool allowRefresh=true, String? path, String method='POST', Map<String,String> headers=const {}}) async {
    if (!RegExp(r'^[a-z][a-z0-9-]*(/[a-z][a-z0-9-]*)*$').hasMatch(operation)) {
      throw ArgumentError('Invalid API operation');
    }
    final generation = _generation;
    try {
      // An explicit operation API avoids sending writable client state or
      // trusting locally calculated approval/dispatch results on the server.
      final target=path??(restAuth?switch(operation){'auth/kakao'=>'auth/sessions/kakao','auth/refresh'=>'auth/tokens/refresh','auth/logout'=>'auth/sessions/logout',_=>operation}:operation);
      final request = http.Request(method, Uri.parse('${baseUrl.replaceFirst(RegExp(r'/+$'), '')}/$target'))
        ..followRedirects = false
        ..headers.addAll({'Content-Type': 'application/json',...headers,
          if (_accessToken != null) 'Authorization': 'Bearer $_accessToken'});
      if(method!='GET')request.body=jsonEncode(data);
      final response = await http.Response.fromStream(await _client.send(request)
        .timeout(const Duration(seconds: 15))).timeout(const Duration(seconds: 15));
      if (generation != _generation) throw const ServiceFailure(499, '요청이 취소되었습니다.');
      if (response.statusCode < 200 || response.statusCode >= 300) {
        if(response.statusCode==401&&allowRefresh&&_refreshToken!=null&&!operation.startsWith('auth/')){
          try{
            _refreshing??=_request('auth/refresh',{'refreshToken':_refreshToken},allowRefresh:false);
            await _refreshing;
          }finally{_refreshing=null;}
          return await _request(operation,data,allowRefresh:false,path:path,method:method,headers:headers);
        }
        if (response.statusCode == 401) clearSession();
        if (kDebugMode && operation=='drivers/register') debugPrint('API $operation failed (HTTP ${response.statusCode}).');
        String? code;
        Object? validationDetails;
        try{final error=jsonDecode(utf8.decode(response.bodyBytes));if(error is Map){if(error['code'] is String)code=error['code'] as String;validationDetails=error['details'];}}catch(_){}
        final businessMessage=switch(code){
          'INVALID_INPUT_VALUE'=>operation=='drivers/register'?_registrationInputMessage(validationDetails):'입력한 정보의 형식과 길이를 확인해 주세요.',
          'INTERNAL_SERVER_ERROR'=>'서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
          'KAKAO_AUTH_FAILED'=>'카카오 인증이 만료되었거나 사용할 수 없습니다. 카카오 로그인부터 다시 진행해 주세요.',
          'KAKAO_SERVICE_UNAVAILABLE'=>'서버에서 카카오 인증을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
          'INVALID_CREDENTIALS'=>'아이디 또는 비밀번호를 확인해 주세요.',
          'ADMIN_LOGIN_ID_ALREADY_EXISTS'=>'이미 사용 중인 관리자 아이디입니다.',
          'BOOTSTRAP_ADMIN_REQUIRED'=>'최초 관리자만 계정을 관리할 수 있습니다.',
          'BOOTSTRAP_ADMIN_PROTECTED'=>'최초 관리자 계정은 삭제하거나 관리 대상으로 변경할 수 없습니다.',
          'ADMIN_ACCOUNT_VERSION_CONFLICT'=>'다른 요청으로 계정이 변경되었습니다. 새로고침해 주세요.',
          'ACCOUNT_DISABLED'=>'비활성화된 계정입니다. 최초 관리자에게 문의해 주세요.',
          'BUSINESS_INFO_INVALID'=>'사업자등록번호·개업일자·대표자명을 확인해 주세요. 계속사업자만 신청할 수 있습니다.',
          'BUSINESS_VERIFICATION_UNAVAILABLE'=>'사업자 확인 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
          _=>null,
        };
        throw ServiceFailure(response.statusCode, businessMessage??switch(response.statusCode) {
          400 => '입력한 정보의 형식과 길이를 확인해 주세요.',
          401 => '인증이 만료되었습니다. 다시 로그인해 주세요.',
          403 => '이 작업에 접근할 권한이 없습니다.',
          404 || 410 => '요청이 삭제되었거나 만료되었습니다.',
          409 => '다른 사용자가 먼저 처리했습니다. 최신 상태를 확인해 주세요.',
          429 => '요청이 많습니다. 잠시 후 다시 시도해 주세요.',
          500 => '서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
          503 => '서버에서 일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.',
          _ => '요청을 처리하지 못했습니다. 다시 시도해 주세요.',
        },code:code);
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

String _registrationInputMessage(Object? details) {
  const fields = {
    'profile.workerName': '근로자명', 'profile.phone': '휴대폰 번호',
    'profile.email': '이메일', 'profile.residence': '거주지 주소',
    'profile.vehicleNumber': '차량번호',
    'profile.settlementAccount.bankName': '은행명',
    'profile.settlementAccount.accountNumber': '계좌번호(6~30자, 숫자와 -)',
    'profile.settlementAccount.holderName': '예금주',
    'profile.taxInvoiceEmail': '세금계산서 이메일',
    'profile.specialties': '전문 분야(1~3개)',
    'profile.activityRegions': '활동 지역', 'profile.equipment': '보유 장비',
    'profile.distinctCollections': '전문 분야·활동 지역·장비의 중복 선택',
    'business.businessName': '사업체명', 'business.businessRegistrationNumber': '사업자등록번호',
    'business.openedOn': '개업일자', 'business.representativeName': '대표자명',
    'business.companyPhone': '회사 대표번호(9~20자, 숫자·+·-)',
    'business.businessType': '업태', 'business.businessItem': '종목',
    'business.companyAddress': '회사 주소',
  };
  final labels = <String>{};
  if (details is List) {
    for (final item in details) {
      if (item is! Map || item['field'] is! String) continue;
      final path = (item['field'] as String).replaceAll(RegExp(r'\[\d+\]'), '');
      final label = fields[path];
      if (label != null) labels.add(label);
    }
  }
  if (labels.isEmpty) return '가입 정보의 형식과 길이를 확인해 주세요.';
  return '${labels.join(', ')} 항목을 확인해 주세요. 이전 버튼으로 돌아가 수정할 수 있습니다.';
}
