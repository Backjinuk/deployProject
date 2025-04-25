INSERT INTO site
  (id, text, home_path,
   java_old, java_new,
   xml_old,  xml_new,
   jsp_old,  jsp_new,
   script_old, script_new,
   user_seq)
VALUES
  -- 1: 대표홈페이지
  (1, '대표홈페이지', '/home/devpart/',
   '/src/main/java/',      '/home/ncrc/webapp/WEB-INF/classes/',
   '/src/main/resources/', '/home/ncrc/webapp/WEB-INF/classes/',
   '/src/main/webapp/WEB-INF/jsp/', '/home/ncrc/webapp/WEB-INF/jsp/',
   '/src/main/webapp/',    '/home/ncrc/webapp/WEB-INF/',
   1),

  -- 2: 아이온
  (2, '아이온', '/home/devpart/',
   '/src/main/com/',        '/webroot/ion.or.kr/html/WEB-INF/classes/com/',
   '/resources/main/com/',  '/webroot/ion.or.kr/html/WEB-INF/classes/',
   '/src/main/webapp/WEB-INF/jsp', '/webroot/ion.or.kr/html/WEB-INF/jsp',
   '/src/main/webapp/',     '/webroot/ion.or.kr/html/WEB-INF/',
   1),

  -- 3: 다돌
  (3, '다돌', '/home/devpart/',
   '/src/main/java/',       '/usr/local/tomcat/webapps/ROOT/WEB-INF/classes/',
   '/src/main/resources/',  '/usr/local/tomcat/webapps/ROOT/WEB-INF/classes/',
   '/src/main/webapp/WEB-INF/', '/usr/local/tomcat/webapps/ROOT/WEB-INF/',
   '/src/main/webapp/',     '/usr/local/tomcat/webapps/ROOT/WEB-INF/',
   1),

  -- 4: 지아센평가(관리자)
  (4, '지아센평가(관리자)', '/home/devpart/',
   '/ncrc_icarevalue_admin/src/main/java/', '/webroot/admin.icarevalue.or.kr/html/WEB-INF/classes/',
   '/ncrc_icarevalue_admin/src/main/resources/','/webroot/admin.icarevalue.or.kr/html/WEB-INF/classes/',
   '/ncrc_icarevalue_admin/src/main/webapp/WEB-INF/','/webroot/admin.icarevalue.or.kr/html/WEB-INF/',
   '/ncrc_icarevalue_admin/src/main/webapp/','/webroot/admin.icarevalue.or.kr/html/WEB-INF/',
   1),

  -- 5: 지아센업무(관리자)
  (5, '지아센업무(관리자)', '/home/devpart/',
   '/src/main/java/',      '/webroot/admin.icareinfo.go.kr/html/WEB-INF/classes/',
   '/src/main/resources/', '/webroot/admin.icareinfo.go.kr/html/WEB-INF/classes/',
   '/src/main/webapp/WEB-INF/','/webroot/admin.icareinfo.go.kr/html/WEB-INF/',
   '/src/main/webapp/',    '/webroot/admin.icareinfo.go.kr/html/WEB-INF/',
   1),

  -- 6: 지아센평가(사용자)
  (6, '지아센평가(사용자)', '/home/devpart/',
   '/src/main/java/',      '/webroot/icarevalue.or.kr/html/WEB-INF/classes/',
   '/src/main/resources/', '/webroot/icarevalue.or.kr/html/WEB-INF/classes/',
   '/src/main/webapp/WEB-INF/','/webroot/icarevalue.or.kr/html/WEB-INF/',
   '/src/main/webapp/',    '/webroot/icarevalue.or.kr/html/WEB-INF/',
   1),

  -- 7: 지아센업무(사용자)
  (7, '지아센업무(사용자)', '/home/devpart/',
   '/src/main/java/',      '/webroot/icareinfo.go.kr/html/WEB-INF/classes/',
   '/src/main/resources/', '/webroot/icareinfo.go.kr/html/WEB-INF/classes/',
   '/src/main/webapp/WEB-INF/','/webroot/icareinfo.go.kr/html/WEB-INF/',
   '/src/main/webapp/',    '/webroot/icareinfo.go.kr/html/WEB-INF/',
   1),

  -- 8: Kisia(키시아)
  (8, 'Kisia(키시아)', '/',
   '/FGI_kisia/src/main/java/',      '/usr/local/tomcat8.5/webapps/ROOT/WEB-INF/classes/',
   '/FGI_kisia/src/main/resources/', '/usr/local/tomcat8.5/webapps/ROOT/WEB-INF/classes/',
   '/FGI_kisia/src/main/webapp/WEB-INF/','/usr/local/tomcat8.5/webapps/ROOT/WEB-INF/jsp/',
   '/FGI_kisia/src/main/webapp/',    '/usr/local/tomcat8.5/webapps/ROOT/',
   1),

  -- 9: 한양대의료원
  (9, '한양대의료원', '/root/',
   '/hanyang/src/main/java/',      '/usr/local/tomcat/webapps/ROOT/WEB-INF/classes/',
   '/hanyang/src/main/resources/','/usr/local/tomcat/webapps/ROOT/WEB-INF/classes/',
   '/hanyang/src/main/webapp/WEB-INF/','/usr/local/tomcat/webapps/ROOT/WEB-INF/',
   '/hanyang/src/main/webapp/',    '/usr/local/tomcat/webapps/ROOT/',
   1),

  -- 10: 수협_웅진
  (10, '수협_웅진', '/home/deploy/',
   'src/main/java/',               '/usr/local/tomcat8_1/ongjin.suhyup.co.kr/ongjin/WEB-INF/classes/',
   'src/main/webapp/WEB-INF/web.xml','/usr/local/tomcat8_1/ongjin.suhyup.co.kr/ongjin/WEB-INF/classes/',
   'src/main/webapp/WEB-INF/',     '/usr/local/tomcat8_1/ongjin.suhyup.co.kr/ongjin/WEB-INF/',
   'src/main/webapp/',             '/usr/local/tomcat8_1/ongjin.suhyup.co.kr/ongjin/',
   1),

  -- 11: 수협_제주어류
  (11, '수협_제주어류', '/home/deploy/',
   'src/main/java/',               '/usr/local/tomcat8_1/jaf.suhyup.co.kr/jaf/WEB-INF/classes/',
   'src/main/resources/',          '/usr/local/tomcat8_1/jaf.suhyup.co.kr/jaf/WEB-INF/classes/',
   'src/main/webapp/WEB-INF/',     '/usr/local/tomcat8_1/jaf.suhyup.co.kr/jaf/WEB-INF/',
   'src/main/webapp/',             '/usr/local/tomcat8_1/jaf.suhyup.co.kr/jaf/',
   1),

  -- 12: 수협_부경신항
  (12, '수협_부경신항', '/home/deploy/',
   'suhyup_bknp/src/main/java/',          '/usr/local/tomcat8_2/bknp.fgi.agency/bknp/WEB-INF/classes/',
   'suhyup_bknp/src/main/resources/',     '/usr/local/tomcat8_2/bknp.fgi.agency/bknp/WEB-INF/classes/',
   'suhyup_bknp/src/main/webapp/WEB-INF/','/usr/local/tomcat8_2/bknp.fgi.agency/bknp/WEB-INF/',
   'suhyup_bknp/src/main/webapp/',        '/usr/local/tomcat8_2/bknp.fgi.agency/bknp/',
   1),

  -- 13: 수협_민물장어양식
  (13, '수협_민물장어양식', '/home/deploy/',
   'suhyup_ecf/src/main/java/',           '/usr/local/tomcat8_2/ecf.fgi.agency/ecf/WEB-INF/classes/',
   'suhyup_ecf/src/main/resources/',      '/usr/local/tomcat8_2/ecf.fgi.agency/ecf/WEB-INF/classes/',
   'suhyup_ecf/src/main/webapp/WEB-INF/','/usr/local/tomcat8_2/ecf.fgi.agency/ecf/WEB-INF/',
   'suhyup_ecf/src/main/webapp/',         '/usr/local/tomcat8_2/ecf.fgi.agency/ecf/',
   1);

