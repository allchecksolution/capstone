import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('business document fields tolerate spaced labels and shared lines', () {
    final fields = BusinessDocumentFields.parse('''
사업자등록증
등록번호 : 123-45-67890
상 호 : 테스트 설비
성 명 : 김기사
사업장 소재지 : 서울특별시 중구 테스트로 10
사업의 종류 : 업 태 : 서비스업 종 목 : 배관 수리
개업연월일 : 2020년 01월 01일
''').values;
    expect(fields, {
      'businessNumber': '123-45-67890',
      'company': '테스트 설비',
      'owner': '김기사',
      'companyAddress': '서울특별시 중구 테스트로 10',
      'businessType': '서비스업',
      'businessItem': '배관 수리',
    });
  });
  test(
    'multi-line label values are suggestions, unrelated dates are not numbers',
    () {
      final fields = BusinessDocumentFields.parse('''
상호(법인명) :
테스트 주식회사
대표자명 : 이기사
등록 번호 : 123 45 67890
생년월일 : 900101
''').values;
      expect(fields['company'], '테스트 주식회사');
      expect(fields['owner'], '이기사');
      expect(fields['businessNumber'], '123-45-67890');
      expect(BusinessDocumentFields.parse('개업연월일 2020112511').values, isEmpty);
    },
  );
  test(
    'missing fields remain missing for manual input, no invented contact data',
    () {
      final fields = BusinessDocumentFields.parse(
        '사업자등록증\n123-45-67890',
      ).values;
      expect(fields, {'businessNumber': '123-45-67890'});
      expect(fields.containsKey('companyPhone'), false);
      expect(fields.containsKey('taxEmail'), false);
      expect(BusinessDocumentFields.parse('').values, isEmpty);
    },
  );
}
