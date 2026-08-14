-- Persisted SKAX reference schedules for every workforce identity.
-- Production delivery replaces this snapshot through HRIS and IdP projections.
-- Variation is deterministic so clean database rebuilds remain reproducible.

CREATE TEMP TABLE tmp_cal_skax_members (
    user_id BIGINT PRIMARY KEY,
    person_public_id UUID NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    account_status VARCHAR(20) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_cal_skax_members (
    user_id, person_public_id, email, display_name, account_status)
VALUES
(5, '3edde887-9716-8950-e7a0-045998101987'::uuid, 'minseo.kim@sk.com', '김민서', 'ACTIVE'),
    (6, '259e4ca1-e9c8-6ad0-c71c-9088ccb5eb85'::uuid, 'elena.garcia@sk.com', 'Elena Garcia', 'INVITED'),
    (7, '245cc981-9c40-e0de-ccdf-e5f8a432d6b5'::uuid, 'jiho.park@sk.com', '박지호', 'INVITED'),
    (8, '57d240ea-bc2c-a562-d4cf-03448dec4d22'::uuid, 'minjun.kim@sk.com', '김민준', 'INVITED'),
    (9, '34f5e51a-2ca6-c6f6-6627-b44f08f31d1d'::uuid, 'seoyeon.lee@sk.com', '이서연', 'ACTIVE'),
    (10, '5af80da3-0dd8-b3bc-2f44-22d90eecaac4'::uuid, 'hyunwoo.park@sk.com', '박현우', 'ACTIVE'),
    (11, '00ba0853-02a8-7499-b6d8-009251e6a464'::uuid, 'yujin.choi@sk.com', '최유진', 'ACTIVE'),
    (12, '5b5d86a1-8c10-76d2-4e7e-3091372a1084'::uuid, 'woosung.jung@sk.com', '정우성', 'INVITED'),
    (13, '5b23538c-61af-a7b4-afb1-1fb31d03e399'::uuid, 'jimin.han@sk.com', '한지민', 'INVITED'),
    (14, '306b543f-741f-6fd3-36bf-48325f3e7e20'::uuid, 'doyun.kim@sk.com', '김도윤', 'ACTIVE'),
    (15, 'e96089af-ead6-2f6a-6111-1d2e15058b1d'::uuid, 'seojin.yoon@sk.com', '윤서진', 'ACTIVE'),
    (16, 'bda29b83-7a8f-ded4-083b-244f055bd6c4'::uuid, 'minseok.jang@sk.com', '장민석', 'ACTIVE'),
    (17, 'a5f8cb68-ae4f-5994-abe2-1642f25040de'::uuid, 'haeun.cho@sk.com', '조하은', 'INVITED'),
    (18, 'ab3efa3a-b522-57f1-02b4-075e7f80d4b0'::uuid, 'jaehyun.lim@sk.com', '임재현', 'INVITED'),
    (19, '20d2c8fb-7256-442f-dcb0-92ba35a3db8b'::uuid, 'sofia.chen@sk.com', 'Sofia Chen', 'INVITED'),
    (20, '71ed1904-1405-e7ce-3f27-0845298ba1e2'::uuid, 'subin.oh@sk.com', '오수빈', 'ACTIVE'),
    (21, '94d55a4f-96de-09fd-5454-bbd64b60ccb3'::uuid, 'taehoon.kang@sk.com', '강태훈', 'ACTIVE'),
    (22, '457477f1-ee4a-9b12-3668-ec7663989ee5'::uuid, 'yerin.moon@sk.com', '문예린', 'ACTIVE'),
    (23, '0a1400f8-c80e-e06a-263a-ae18528c1a58'::uuid, 'junho.song@sk.com', '송준호', 'INVITED'),
    (24, 'a3e07946-57b1-4441-ae00-d14ad9eb284c'::uuid, 'jiwoo.bae@sk.com', '배지우', 'ACTIVE'),
    (25, '94236bf8-cfd0-a954-36fd-7f5b594082c1'::uuid, 'alex.morgan@sk.com', 'Alex Morgan', 'INVITED'),
    (28, 'ff5608f2-9eed-04a2-9bbd-3effd1b6eaa5'::uuid, 'yejun.shin@sk.com', '신예준', 'INVITED'),
    (29, 'd4bc013d-8c7a-fbcb-be2a-7d83286e0b18'::uuid, 'chaewon.kim@sk.com', '김채원', 'ACTIVE'),
    (30, 'f6d51476-31f7-e9af-5582-0c74dcd0fca9'::uuid, 'minjae.ryu@sk.com', '류민재', 'INVITED'),
    (31, '5a1cc8ce-ae60-8d42-e0c2-f488d720a260'::uuid, 'arin.seo@sk.com', '서아린', 'INVITED'),
    (32, '6edd429e-6650-00a3-d68a-2bd4cc954551'::uuid, 'seowoo.jung@sk.com', '정서우', 'ACTIVE'),
    (33, 'd1b648ab-318d-824d-50f6-11c418b75f9a'::uuid, 'dohyun.lee@sk.com', '이도현', 'ACTIVE'),
    (34, 'adbe7ea7-f6e0-1c62-3b7d-e566d779225c'::uuid, 'nayeon.park@sk.com', '박나연', 'INVITED'),
    (35, '6625e4a8-eaa9-c5d7-20bc-47f2029677b3'::uuid, 'gunwoo.choi@sk.com', '최건우', 'ACTIVE'),
    (36, 'fe1cd352-cda1-776d-51a6-9b393649fc8a'::uuid, 'emily.johnson@sk.com', 'Emily Johnson', 'INVITED'),
    (37, '073c6aef-f778-94ac-4bb3-0e355fa41dbc'::uuid, 'jisoo.hong@sk.com', '홍지수', 'ACTIVE'),
    (38, '3490c134-c01b-d32d-eda2-f257c94496f2'::uuid, 'doyoon.nam@sk.com', '남도윤', 'ACTIVE'),
    (39, 'a6145a42-3539-8504-b682-164491286876'::uuid, 'seoyoon.ko@sk.com', '고서윤', 'INVITED'),
    (40, '6dddb2e7-e311-0455-2c15-55d1ff0e2379'::uuid, 'taeyeon.kim@sk.com', '김태연', 'ACTIVE'),
    (41, 'cc4804fd-f65a-998f-b162-4c2d594ec767'::uuid, 'seungmin.yoo@sk.com', '유승민', 'ACTIVE'),
    (42, 'aaf32653-4578-46a9-c679-7302615e84cc'::uuid, 'james.wilson@sk.com', 'James Wilson', 'ACTIVE'),
    (43, 'c494f0f5-bde9-77cd-45d2-378d304b2794'::uuid, 'harin.noh@sk.com', '노하린', 'INVITED'),
    (44, 'b635ba14-d13b-30e0-f2a1-036660b37e6f'::uuid, 'jihoon.ahn@sk.com', '안지훈', 'INVITED'),
    (45, '10027dc8-f080-d290-881f-3221e03e6bf3'::uuid, 'yeeun.baek@sk.com', '백예은', 'INVITED'),
    (47, '92172441-1864-53f6-1737-99ba5dbb468b'::uuid, 'minsung.kwon@sk.com', '권민성', 'INVITED'),
    (48, '80c425a6-8834-d830-8b10-88d783c46630'::uuid, 'raon.kim@sk.com', '김라온', 'INVITED'),
    (49, '78525b6c-8587-1a4f-3e94-d0c65c82d167'::uuid, 'junseo.na@sk.com', '나준서', 'INVITED'),
    (50, '1aee14e6-d7b3-57c7-9bd1-e1886de298d5'::uuid, 'yuna.jeon@sk.com', '전유나', 'INVITED'),
    (51, '9b3ad015-ff40-2133-b081-0ed7098849bc'::uuid, 'rfl001@sk.com', '김수아', 'INVITED'),
    (52, '92d8c040-8842-bf9c-0065-f27d34c716cf'::uuid, 'rfl002@sk.com', '이준호', 'INVITED'),
    (53, '788d86d7-e3b8-c1a6-c52b-aa0a2f5af0cd'::uuid, 'rfl003@sk.com', '박민재', 'INVITED'),
    (54, 'de9edec9-f3ab-32fb-3b2f-15cb0ee9dda0'::uuid, 'rfl004@sk.com', '최유진', 'INVITED'),
    (55, 'c0f5a6ab-7200-a718-2cec-c594775cbabc'::uuid, 'rfl005@sk.com', '정서준', 'INVITED'),
    (56, 'daa04cf0-2548-5648-a751-f344cc2b3e33'::uuid, 'rfl006@sk.com', '강태민', 'INVITED'),
    (57, '2fb1ec14-6d3e-ce6b-a39e-7a554719e554'::uuid, 'rfl007@sk.com', '조서진', 'INVITED'),
    (58, '14dd8533-606e-26d8-99d2-6c1f9940bbbe'::uuid, 'rfl008@sk.com', '윤예린', 'INVITED'),
    (59, 'b732d81e-df58-9bf2-9078-cdb443848b85'::uuid, 'rfl009@sk.com', '장현우', 'INVITED'),
    (60, 'fa6d1350-0cda-a419-38c5-5f4259c481f8'::uuid, 'rfl010@sk.com', '임지우', 'INVITED'),
    (61, '16be2c19-8cfa-da64-eabd-a54c50921c97'::uuid, 'rfl011@sk.com', '한채원', 'INVITED'),
    (62, 'ecf0f047-5ec9-bff5-8d6e-47eca3a51ef0'::uuid, 'rfl012@sk.com', '김도윤', 'INVITED'),
    (63, 'b98ebf0a-09e2-bb78-5838-577f4b2c5a44'::uuid, 'rfl013@sk.com', '이도현', 'INVITED'),
    (64, '226b0773-393f-42bd-3212-1079137231f3'::uuid, 'rfl014@sk.com', '박아린', 'INVITED'),
    (65, 'f0a535dd-def4-37e6-6109-a3b41c60726a'::uuid, 'rfl015@sk.com', '최하윤', 'INVITED'),
    (66, '28cf7056-faf7-1b3b-005b-6d18d370edd7'::uuid, 'rfl016@sk.com', '정시우', 'INVITED'),
    (67, '16050193-52cf-3bd5-3172-910d31f01e4e'::uuid, 'rfl017@sk.com', '강지민', 'INVITED'),
    (68, 'ac2b5cd2-5c69-919f-2058-27f2cfff9d9a'::uuid, 'rfl018@sk.com', '조수아', 'INVITED'),
    (69, '8acb3e52-d099-2741-1c2b-e051cb54d5ec'::uuid, 'rfl019@sk.com', '윤준호', 'INVITED'),
    (70, 'fead22b8-af92-7b18-3958-a99a1ecd786d'::uuid, 'rfl020@sk.com', '장민재', 'INVITED'),
    (71, '9a5e4331-bed0-179c-216c-097d0d9c5428'::uuid, 'rfl021@sk.com', '임유진', 'INVITED'),
    (72, '6b5461f7-3640-55b6-48c4-8834ac3e40c3'::uuid, 'rfl022@sk.com', '한서준', 'INVITED'),
    (73, 'ea19dc1b-3003-0929-273e-2fdd1219d866'::uuid, 'rfl023@sk.com', '김태민', 'INVITED'),
    (74, '8300d663-a99f-5d25-26fd-3e7dc14392a9'::uuid, 'rfl024@sk.com', '이서진', 'INVITED'),
    (75, 'd874a195-6ef2-cd07-2ed5-7cd6d7f1f511'::uuid, 'rfl025@sk.com', '박예린', 'INVITED'),
    (76, '3d390134-fd6c-6d9a-c18f-2c633c81a6b6'::uuid, 'rfl026@sk.com', '최현우', 'INVITED'),
    (77, '52ba00db-d141-77df-4f31-394a9d11a71f'::uuid, 'rfl027@sk.com', '정지우', 'INVITED'),
    (78, '419e92c9-6a00-5ac7-92b4-70479f40824b'::uuid, 'rfl028@sk.com', '강채원', 'INVITED'),
    (79, '0616b8fb-df72-0760-a87c-750b0d21eb78'::uuid, 'rfl029@sk.com', '조도윤', 'INVITED'),
    (80, '85fa6273-49e1-f6e4-5f7b-a264bf2ae7ad'::uuid, 'rfl030@sk.com', '윤도현', 'INVITED'),
    (81, '423bbf9d-24fe-350c-b142-2d9ab1cf5827'::uuid, 'rfl031@sk.com', '장아린', 'INVITED'),
    (82, '059def2c-1664-c6ae-e2fe-d3eb4b0435ad'::uuid, 'rfl032@sk.com', '임하윤', 'INVITED'),
    (83, 'dc09791f-8bd4-b945-9546-4b145b27faf4'::uuid, 'rfl033@sk.com', '한시우', 'INVITED'),
    (84, '03061711-89bf-0af7-591f-f445d57946d0'::uuid, 'rfl034@sk.com', '김지민', 'INVITED'),
    (85, '212baf74-6df6-c60f-f010-90608cd78483'::uuid, 'rfl035@sk.com', '이수아', 'INVITED'),
    (86, '1a2ca008-cc0a-523b-3915-a7fe0867fc40'::uuid, 'rfl036@sk.com', '박준호', 'INVITED'),
    (87, 'd6b0b5d4-f1be-b0bf-e96a-be68c6fd43d2'::uuid, 'rfl037@sk.com', '최민재', 'INVITED'),
    (88, '629638b3-48c9-756e-de64-60590e82076f'::uuid, 'rfl038@sk.com', '정유진', 'INVITED'),
    (89, '33373cc0-90f2-1f35-b916-d751517a1db2'::uuid, 'rfl039@sk.com', '강서준', 'INVITED'),
    (90, '63aedd96-0fe7-afbc-a2e4-3d18c53708f2'::uuid, 'rfl040@sk.com', '조태민', 'INVITED'),
    (91, 'ba6253e3-2b9e-ca63-212b-84af36427bf0'::uuid, 'rfl041@sk.com', '윤서진', 'INVITED'),
    (92, '9184121a-b659-fa00-7ee8-705d459974be'::uuid, 'rfl042@sk.com', '장예린', 'INVITED'),
    (93, 'bbb9881b-615c-1f0a-2ca1-75bcdef986d2'::uuid, 'rfl043@sk.com', '임현우', 'INVITED'),
    (94, '6e7c0ee1-75a8-0e70-eebf-4c327ca5721c'::uuid, 'rfl044@sk.com', '한지우', 'INVITED'),
    (95, '7c6dc694-589c-e373-e255-02d8b3de1169'::uuid, 'rfl045@sk.com', '김채원', 'INVITED'),
    (96, '794083e6-f099-28cb-9007-c7311f316b58'::uuid, 'rfl046@sk.com', '이도윤', 'INVITED'),
    (97, '7283c78e-e03c-baf3-12d2-afcb19e5ed6e'::uuid, 'rfm0021@sk.com', '김지우', 'INVITED'),
    (98, 'd8886f9d-cf24-acff-dd05-b1157cae25aa'::uuid, 'rfm0022@sk.com', '이하윤', 'INVITED'),
    (99, '7d3a81a0-9fbb-c818-19e9-5936b95c6481'::uuid, 'rfm0023@sk.com', '박민재', 'INVITED'),
    (100, '023eb83f-0a4b-6ee7-a5c4-77b5c5c3d834'::uuid, 'rfm0031@sk.com', '최수아', 'INVITED'),
    (101, 'c08bec9d-2836-f2b5-47f9-4abb260ea5aa'::uuid, 'rfm0032@sk.com', '정태민', 'INVITED'),
    (102, 'd7ce2e42-5c78-8d89-59e6-6efaffc5fa93'::uuid, 'rfm0033@sk.com', '강채원', 'INVITED'),
    (103, '4d83cb83-8948-67c7-c8a6-bb689069250b'::uuid, 'rfm0041@sk.com', '조현우', 'INVITED'),
    (104, '4103947e-2953-fd57-6598-74fa217b882e'::uuid, 'rfm0042@sk.com', '윤아린', 'INVITED'),
    (105, 'a82dda49-fb4f-5e4e-befe-b09290352404'::uuid, 'rfm0051@sk.com', '임지민', 'INVITED'),
    (106, 'e25150cc-9b94-e0f2-ae8a-751b02bf0e45'::uuid, 'rfm0052@sk.com', '한서준', 'INVITED'),
    (107, 'bbc4de93-0edb-1db4-a91c-7ebe3020a6d2'::uuid, 'rfm0071@sk.com', '정시우', 'INVITED'),
    (108, 'c75ca25d-3e93-5090-d85e-5fd001a77ee3'::uuid, 'rfm0072@sk.com', '강유진', 'INVITED'),
    (109, '4a64e0ca-dd14-3bd9-5f06-e7cebc95feb5'::uuid, 'rfm0073@sk.com', '조현우', 'INVITED'),
    (110, 'c508a107-1f12-6bab-d01b-c68c763d80db'::uuid, 'rfm0081@sk.com', '윤서진', 'INVITED'),
    (111, 'ec0d33cf-292a-3a4d-ac35-f6a426fb66f1'::uuid, 'rfm0082@sk.com', '장도윤', 'INVITED'),
    (112, 'e131a7df-a61e-2318-81be-45a557452ea9'::uuid, 'rfm0091@sk.com', '한하윤', 'INVITED'),
    (113, '62bf848e-250a-3a44-2468-1d6579443bdc'::uuid, 'rfm0092@sk.com', '김민재', 'INVITED'),
    (114, '115ab7f5-fc23-1892-03c9-0cf755d4309e'::uuid, 'rfm0093@sk.com', '이예린', 'INVITED'),
    (115, '68ad5add-2731-dadc-6afa-a1b80665edd4'::uuid, 'rfm0111@sk.com', '강아린', 'INVITED'),
    (116, '7d3d9762-af46-37ce-082e-cd6b1f0479bb'::uuid, 'rfm0112@sk.com', '조준호', 'INVITED'),
    (117, '3a651972-c7f3-f924-4691-8436d9fd834b'::uuid, 'rfm0113@sk.com', '윤서진', 'INVITED'),
    (118, 'ba72fe2d-ad64-fb5e-431b-35a294d7f207'::uuid, 'rfm0121@sk.com', '장서준', 'INVITED'),
    (119, 'ba0a3a10-257c-c9b1-6db1-44d305937b91'::uuid, 'rfm0122@sk.com', '임지우', 'INVITED'),
    (120, '09f9dfbe-ecf7-279e-e7bd-e05eb8cafb61'::uuid, 'rfm0123@sk.com', '한하윤', 'INVITED'),
    (121, '3a9904d0-62b9-c318-6e69-3988d1a696ef'::uuid, 'rfm0131@sk.com', '김도현', 'INVITED'),
    (122, '1d8e7936-54de-1e57-1874-fd801d23d6c0'::uuid, 'rfm0132@sk.com', '이수아', 'INVITED'),
    (123, 'd35ad170-d159-5db5-78cf-35a55d80de68'::uuid, 'rfm0133@sk.com', '박태민', 'INVITED'),
    (124, '3367fb9a-70c0-f22f-7dcd-0600bfbdf575'::uuid, 'rfm0141@sk.com', '최유진', 'INVITED'),
    (125, 'd655cee8-8659-402b-cb7c-e2ba86e7043f'::uuid, 'rfm0142@sk.com', '정현우', 'INVITED'),
    (126, 'fa0a6486-a6f3-f489-cd42-2b4bb017c81d'::uuid, 'rfm0143@sk.com', '강아린', 'INVITED'),
    (127, 'e76f4e2a-b249-f69b-95ca-1ca3612c0381'::uuid, 'rfm0161@sk.com', '임민재', 'INVITED'),
    (128, '85eb3919-9479-d7a9-a5a9-478b112e8820'::uuid, 'rfm0162@sk.com', '한예린', 'INVITED'),
    (129, 'e1c45de9-d3e9-31b2-2829-3491b0dd2da2'::uuid, 'rfm0163@sk.com', '김도현', 'INVITED'),
    (130, 'a517abbf-28a6-d487-d681-67b201374e80'::uuid, 'rfm0171@sk.com', '이채원', 'INVITED'),
    (131, '8c30a772-ee9c-9873-c4c1-f79a48fbbb62'::uuid, 'rfm0172@sk.com', '박시우', 'INVITED'),
    (132, '43d1882b-669c-1564-5692-01f528f2377a'::uuid, 'rfm0173@sk.com', '최유진', 'INVITED'),
    (133, '5f45265a-dbd9-c434-5f03-28d70edd0ca3'::uuid, 'rfm0191@sk.com', '윤지우', 'INVITED'),
    (134, '3337b678-d035-b45f-0293-bea210262b93'::uuid, 'rfm0192@sk.com', '장하윤', 'INVITED'),
    (135, '2c658709-b68a-5388-4f06-911eb46785ab'::uuid, 'rfm0193@sk.com', '임민재', 'INVITED'),
    (136, '6a3815ed-d3d5-3d07-0ee4-dd585877a61f'::uuid, 'rfm0201@sk.com', '한수아', 'INVITED'),
    (137, 'ccdf0868-9a5b-22d3-1b9e-3d653ff34f65'::uuid, 'rfm0202@sk.com', '김태민', 'INVITED'),
    (138, '56addccd-9820-ccd9-5e06-4bd6ea790195'::uuid, 'rfm0203@sk.com', '이채원', 'INVITED'),
    (139, '4766d6fb-4c86-810c-8012-c0cf55249c4e'::uuid, 'rfm0221@sk.com', '강지민', 'INVITED'),
    (140, '80e438b5-e8e4-9ce4-24b2-eead717d3168'::uuid, 'rfm0222@sk.com', '조서준', 'INVITED'),
    (141, 'e675f06c-a45c-5556-8305-8b8ccc4b02c7'::uuid, 'rfm0231@sk.com', '장예린', 'INVITED'),
    (142, 'e0e84677-d958-0c5b-019e-9337061abc06'::uuid, 'rfm0232@sk.com', '임도현', 'INVITED'),
    (143, '88663080-9828-a9b6-99b4-886cf487c9eb'::uuid, 'rfm0251@sk.com', '최서진', 'INVITED'),
    (144, '00c67545-ecef-5f46-bcf0-04593efa3350'::uuid, 'rfm0252@sk.com', '정도윤', 'INVITED'),
    (145, 'f639df99-09d4-cc52-81de-f43ddf401546'::uuid, 'rfm0253@sk.com', '강지민', 'INVITED'),
    (146, '095f9ce4-d839-b5ad-15b9-38e2aeaee545'::uuid, 'rfm0261@sk.com', '조하윤', 'INVITED'),
    (147, 'af489b8a-9c14-90fd-fdb0-fbe30804d7fa'::uuid, 'rfm0262@sk.com', '윤민재', 'INVITED'),
    (148, '4b5a1321-ddac-8ded-24c9-e0ceac81d35d'::uuid, 'rfm0263@sk.com', '장예린', 'INVITED'),
    (149, '446ab86d-e47f-6540-c50e-81a6f0077a31'::uuid, 'rfm0281@sk.com', '이아린', 'INVITED'),
    (150, 'cfb4e1df-e6cc-3662-aa7c-219739b8ee09'::uuid, 'rfm0282@sk.com', '박준호', 'INVITED'),
    (151, '764619fe-c98a-0dbf-2084-4f8b55fc56f6'::uuid, 'rfm0283@sk.com', '최서진', 'INVITED'),
    (152, '82f3918f-524a-8388-d58a-230148b74665'::uuid, 'rfm0291@sk.com', '정서준', 'INVITED'),
    (153, 'd8b01ad3-edb2-9316-3997-ddd58397e045'::uuid, 'rfm0292@sk.com', '강지우', 'INVITED'),
    (154, '7ddece5d-bbb1-3de3-0d64-ab52fa56c4a0'::uuid, 'rfm0293@sk.com', '조하윤', 'INVITED'),
    (155, 'd268504d-f0bf-c26c-5ac5-de25b6ac5ba3'::uuid, 'rfm0321@sk.com', '박도윤', 'INVITED'),
    (156, 'b8795045-f569-cb35-4b6b-ba4d87ccd5f1'::uuid, 'rfm0322@sk.com', '최지민', 'INVITED'),
    (157, 'ae153b51-1753-6db6-6854-b28df2fc1841'::uuid, 'rfm0323@sk.com', '정서준', 'INVITED'),
    (158, 'a8ec7d28-9747-2ab0-475f-8eb12454c715'::uuid, 'rfm0331@sk.com', '강민재', 'INVITED'),
    (159, 'c396a197-a631-3643-6c49-95d92ea91385'::uuid, 'rfm0332@sk.com', '조예린', 'INVITED'),
    (160, '732a8715-bd06-176f-5299-59746375696e'::uuid, 'rfm0333@sk.com', '윤도현', 'INVITED'),
    (161, '5c64f7e0-a828-57a8-3a2d-e500b2108518'::uuid, 'rfm0341@sk.com', '장채원', 'INVITED'),
    (162, '55988f5a-ab2c-9355-4e1e-383c0ba0cdbf'::uuid, 'rfm0342@sk.com', '임시우', 'INVITED'),
    (163, '31fcdcfa-5f22-eecf-d84d-0a8f4ccb3c04'::uuid, 'rfm0343@sk.com', '한유진', 'INVITED'),
    (164, '578b1741-dec4-85a4-7ad4-6558ec75b96d'::uuid, 'rfm0371@sk.com', '조수아', 'INVITED'),
    (165, 'f160eee3-f722-8566-5a33-51c543708fc0'::uuid, 'rfm0372@sk.com', '윤태민', 'INVITED'),
    (166, 'db3fc03a-c92d-51ec-e2e9-c65fa05bb592'::uuid, 'rfm0373@sk.com', '장채원', 'INVITED'),
    (167, 'a2147fb2-c462-35c2-af36-cd3898728d5f'::uuid, 'rfm0381@sk.com', '임현우', 'INVITED'),
    (168, '68ac7a68-c447-af78-5964-b6858efdf935'::uuid, 'rfm0382@sk.com', '한아린', 'INVITED'),
    (169, 'fb3e0d30-70af-b59a-ae04-31b8f70817ec'::uuid, 'rfm0383@sk.com', '김준호', 'INVITED'),
    (170, '03d12310-5698-076e-bf0c-ca73e7e45d2f'::uuid, 'rfm0391@sk.com', '이지민', 'INVITED'),
    (171, 'f5ac76c1-db71-f5f2-ce13-7df5f4377b0a'::uuid, 'rfm0392@sk.com', '박서준', 'INVITED'),
    (172, 'ac5691de-1561-d136-5f02-098637660684'::uuid, 'rfm0393@sk.com', '최지우', 'INVITED'),
    (173, 'a1dc8c33-789e-fb36-5858-00c74f5c7c8b'::uuid, 'rfm0401@sk.com', '정예린', 'INVITED'),
    (174, '9bd8938e-81e0-4e07-0fe3-7877b8732ca6'::uuid, 'rfm0402@sk.com', '강도현', 'INVITED'),
    (175, 'f04da33f-5bb3-9ff6-c6c1-ff873a7f5888'::uuid, 'rfm0403@sk.com', '조수아', 'INVITED'),
    (176, '08499c46-b3d6-428a-504e-af1e89b44acb'::uuid, 'rfm0431@sk.com', '박하윤', 'INVITED'),
    (177, 'adab6482-71ac-fb2f-48fa-2e6f54b4201a'::uuid, 'rfm0432@sk.com', '최민재', 'INVITED'),
    (178, 'e16e432a-8d23-e3d8-3c82-f50ee71c17d5'::uuid, 'rfm0433@sk.com', '정예린', 'INVITED'),
    (179, 'f489e163-2b44-2240-2d28-1ceb06d7304b'::uuid, 'rfm0441@sk.com', '강태민', 'INVITED'),
    (180, 'eae5a7bf-a051-2568-5a98-ae0359fa59e1'::uuid, 'rfm0442@sk.com', '조채원', 'INVITED'),
    (181, '5e0538f5-e2f8-acb8-8364-df3743e65bbb'::uuid, 'rfm0443@sk.com', '윤시우', 'INVITED'),
    (182, '5ceb7f59-82fd-9b1f-692e-ba6f3fae6bd2'::uuid, 'rfm0461@sk.com', '김서준', 'INVITED'),
    (183, '944ed30b-adab-bf6f-f3b5-cfa37b11b0cb'::uuid, 'rfm0462@sk.com', '이지우', 'INVITED'),
    (184, 'b7c40283-8b1e-f105-e3a4-9631390f0af3'::uuid, 'rfm0463@sk.com', '박하윤', 'INVITED'),
    (900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid, 'joonbin@sk.com', '최준빈', 'ACTIVE');

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM tmp_cal_skax_members) <> 178 THEN
        RAISE EXCEPTION 'Expected 178 SKAX workforce identities';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_cal_skax_members
         WHERE email <> LOWER(email)
            OR email NOT LIKE '%@sk.com'
            OR account_status NOT IN ('ACTIVE', 'INVITED')
    ) THEN
        RAISE EXCEPTION 'SKAX calendar seed identity contract is invalid';
    END IF;
END
$$;

INSERT INTO cal_identity_links (
    tenant_id, user_id, person_public_id, first_seen_at, last_seen_at)
SELECT tenant.tenant_id, member.user_id, member.person_public_id,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM sys_service_tenants tenant
 CROSS JOIN tmp_cal_skax_members member
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, user_id) DO UPDATE SET
    person_public_id = EXCLUDED.person_public_id;

INSERT INTO cal_calendars (
    calendar_id, tenant_id, calendar_key, owner_user_id, name_ko, name_en,
    color_hex, calendar_type, visibility, lifecycle_state, created_by, updated_by)
SELECT md5('calendar:personal:' || tenant.tenant_id || ':' || member.user_id)::uuid,
       tenant.tenant_id, 'personal-' || member.user_id, member.user_id,
       '내 캘린더', 'My calendar', '#2563EB', 'PERSONAL', 'PRIVATE',
       'ACTIVE', member.user_id, member.user_id
  FROM sys_service_tenants tenant
 CROSS JOIN tmp_cal_skax_members member
 WHERE tenant.tenant_key = 'default'
ON CONFLICT (tenant_id, calendar_key) DO UPDATE SET
    owner_user_id = EXCLUDED.owner_user_id,
    lifecycle_state = 'ACTIVE',
    version = cal_calendars.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

WITH ranked_members AS (
    SELECT member.*,
           ROW_NUMBER() OVER (ORDER BY member.user_id)::integer AS seed_rank
      FROM tmp_cal_skax_members member
), standard_templates AS (
    SELECT *
      FROM (VALUES
        ('weekly-plan', 0, 8, 45, 'TASK', 'WEEKLY', 'DEFAULT', FALSE),
        ('team-sync', 1, 9, 50, 'MEETING', 'WEEKLY', 'DEFAULT', TRUE),
        ('focus', 2, 13, 90, 'FOCUS', 'WEEKLY', 'PRIVATE', FALSE),
        ('one-on-one', 3, 9, 30, 'MEETING', 'WEEKLY', 'DEFAULT', TRUE),
        ('review', 4, 14, 50, 'MEETING', 'WEEKLY', 'DEFAULT', TRUE)
      ) seed(
          seed_key, day_offset, base_hour, duration_minutes, event_type,
          recurrence_pattern, visibility, response_required)
), resolved AS (
    SELECT tenant.tenant_id, calendar.calendar_id, member.*,
           seed.*,
           date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + make_interval(
                   days => seed.day_offset,
                   hours => seed.base_hour
                       + CASE seed.seed_key
                           WHEN 'weekly-plan' THEN MOD(member.seed_rank, 3)
                           WHEN 'team-sync' THEN MOD(member.seed_rank, 5)
                           WHEN 'focus' THEN MOD(member.seed_rank, 4)
                           WHEN 'one-on-one' THEN MOD(member.seed_rank, 7)
                           ELSE MOD(member.seed_rank, 3)
                         END,
                   mins => CASE
                       WHEN seed.seed_key = 'weekly-plan'
                           THEN 15 * MOD(member.seed_rank, 2)
                       ELSE 0
                   END
               ) AS starts_at_local,
           seed.duration_minutes
               + CASE
                   WHEN seed.seed_key = 'focus'
                       THEN 30 * MOD(member.seed_rank, 2)
                   ELSE 0
                 END AS resolved_duration,
           CASE seed.seed_key
               WHEN 'weekly-plan' THEN
                   (ARRAY[
                       '이번 주 핵심 과제 정리',
                       '고객 가치 우선순위 점검',
                       '프로젝트 실행 계획',
                       '주간 업무 설계'
                   ])[1 + MOD(member.seed_rank, 4)]
               WHEN 'team-sync' THEN
                   (ARRAY[
                       '팀 실행 싱크',
                       '프로젝트 진행 점검',
                       '고객 과제 협업 회의',
                       '주간 딜리버리 점검'
                   ])[1 + MOD(member.seed_rank, 4)]
               WHEN 'focus' THEN
                   (ARRAY[
                       '집중 업무 · 핵심 과제',
                       'Deep Work · 설계 집중',
                       '집중 업무 · 품질 개선',
                       '방해 없는 실행 시간'
                   ])[1 + MOD(member.seed_rank, 4)]
               WHEN 'one-on-one' THEN
                   (ARRAY[
                       '1:1 성장 체크인',
                       '업무 맥락 1:1',
                       '피드백 및 지원 논의',
                       '커리어 체크인'
                   ])[1 + MOD(member.seed_rank, 4)]
               ELSE
                   (ARRAY[
                       '주간 성과 리뷰',
                       '이번 주 회고와 다음 행동',
                       '프로젝트 품질 리뷰',
                       '딜리버리 마감 점검'
                   ])[1 + MOD(member.seed_rank, 4)]
           END AS resolved_title
      FROM ranked_members member
      JOIN sys_service_tenants tenant ON tenant.tenant_key = 'default'
      JOIN cal_calendars calendar
        ON calendar.tenant_id = tenant.tenant_id
       AND calendar.calendar_key = 'personal-' || member.user_id
      CROSS JOIN standard_templates seed
)
INSERT INTO cal_events (
    event_id, tenant_id, calendar_id, organizer_user_id,
    organizer_person_public_id, organizer_name, organizer_email,
    title, description, event_type, starts_at, ends_at, time_zone,
    all_day, location, conference_url, status, visibility,
    recurrence_pattern, recurrence_interval, recurrence_until,
    response_required, source_type, source_ref, idempotency_key,
    created_by, updated_by)
SELECT md5(
           'calendar:event:' || resolved.tenant_id || ':'
               || resolved.user_id || ':' || resolved.seed_key)::uuid,
       resolved.tenant_id, resolved.calendar_id, resolved.user_id,
       resolved.person_public_id, resolved.display_name, resolved.email,
       resolved.resolved_title,
       CASE resolved.event_type
           WHEN 'FOCUS'
               THEN '알림을 최소화하고 핵심 과제에 몰입하는 보호 시간입니다.'
           WHEN 'TASK'
               THEN '우선순위와 완료 기준을 확인하고 실행 순서를 정리합니다.'
           ELSE '참석자와 안건, 다음 행동을 함께 확인하는 업무 일정입니다.'
       END,
       resolved.event_type,
       resolved.starts_at_local AT TIME ZONE 'Asia/Seoul',
       (resolved.starts_at_local
           + make_interval(mins => resolved.resolved_duration))
           AT TIME ZONE 'Asia/Seoul',
       'Asia/Seoul', FALSE,
       CASE resolved.seed_key
           WHEN 'team-sync' THEN '온라인 협업 공간'
           WHEN 'one-on-one' THEN '온라인'
           WHEN 'review' THEN '온라인'
           ELSE NULL
       END,
       CASE
           WHEN resolved.seed_key IN ('team-sync', 'one-on-one', 'review')
               THEN 'https://meet.dwp.local/skax/'
                   || resolved.user_id || '/' || resolved.seed_key
           ELSE NULL
       END,
       'CONFIRMED', resolved.visibility, resolved.recurrence_pattern, 1,
       (date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
           + INTERVAL '90 days')::date,
       resolved.response_required, 'NATIVE',
       'seed:skax:' || resolved.seed_key || ':' || resolved.user_id,
       md5(
           'calendar:idempotency:' || resolved.tenant_id || ':'
               || resolved.user_id || ':' || resolved.seed_key)::uuid,
       resolved.user_id, resolved.user_id
  FROM resolved
ON CONFLICT (event_id) DO UPDATE SET
    organizer_person_public_id = EXCLUDED.organizer_person_public_id,
    organizer_name = EXCLUDED.organizer_name,
    organizer_email = EXCLUDED.organizer_email,
    title = EXCLUDED.title,
    description = EXCLUDED.description,
    recurrence_until = EXCLUDED.recurrence_until,
    response_required = EXCLUDED.response_required,
    source_ref = EXCLUDED.source_ref,
    idempotency_key = COALESCE(
        cal_events.idempotency_key, EXCLUDED.idempotency_key),
    version = cal_events.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

-- Normalize pre-existing reference events that used a generic organizer label.
UPDATE cal_events event
   SET organizer_person_public_id = member.person_public_id,
       organizer_name = member.display_name,
       organizer_email = member.email,
       version = event.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
  FROM tmp_cal_skax_members member
 WHERE event.organizer_user_id = member.user_id
   AND (
       event.organizer_person_public_id IS DISTINCT FROM member.person_public_id
       OR event.organizer_name IS DISTINCT FROM member.display_name
       OR event.organizer_email IS DISTINCT FROM member.email
   );

CREATE TEMP TABLE tmp_cal_skax_peers ON COMMIT DROP AS
WITH ranked AS (
    SELECT member.*,
           ROW_NUMBER() OVER (ORDER BY member.user_id)::integer AS seed_rank,
           LEAD(member.user_id) OVER (ORDER BY member.user_id) AS next_user_id
      FROM tmp_cal_skax_members member
)
SELECT organizer.user_id AS organizer_user_id,
       organizer.seed_rank,
       peer.user_id AS peer_user_id,
       peer.person_public_id AS peer_person_public_id,
       peer.email AS peer_email,
       peer.display_name AS peer_name
  FROM ranked organizer
  JOIN tmp_cal_skax_members peer
    ON peer.user_id = COALESCE(
        organizer.next_user_id,
        (SELECT MIN(user_id) FROM tmp_cal_skax_members));

WITH tenant AS (
    SELECT tenant_id
      FROM sys_service_tenants
     WHERE tenant_key = 'default'
), peer_events AS (
    SELECT peer.*,
           seed.seed_key,
           md5(
               'calendar:event:' || tenant.tenant_id || ':'
                   || peer.organizer_user_id || ':' || seed.seed_key)::uuid
               AS event_id,
           tenant.tenant_id
      FROM tmp_cal_skax_peers peer
     CROSS JOIN tenant
     CROSS JOIN (VALUES ('team-sync'), ('one-on-one')) seed(seed_key)
)
INSERT INTO cal_event_attendees (
    attendee_id, tenant_id, event_id, attendee_user_id,
    attendee_person_public_id, attendee_email, attendee_name,
    attendee_type, response_status, responded_at)
SELECT md5(
           'calendar:attendee:peer:' || peer_event.event_id || ':'
               || peer_event.peer_user_id)::uuid,
       peer_event.tenant_id, peer_event.event_id, peer_event.peer_user_id,
       peer_event.peer_person_public_id, peer_event.peer_email,
       peer_event.peer_name, 'REQUIRED',
       CASE MOD(peer_event.seed_rank + LENGTH(peer_event.seed_key), 5)
           WHEN 0 THEN 'NEEDS_ACTION'
           WHEN 1 THEN 'TENTATIVE'
           WHEN 2 THEN 'DECLINED'
           ELSE 'ACCEPTED'
       END,
       CASE MOD(peer_event.seed_rank + LENGTH(peer_event.seed_key), 5)
           WHEN 0 THEN NULL
           ELSE CURRENT_TIMESTAMP
               - make_interval(hours => 2 + MOD(peer_event.seed_rank, 36))
       END
  FROM peer_events peer_event
  JOIN cal_events event ON event.event_id = peer_event.event_id
ON CONFLICT (event_id, attendee_email) DO UPDATE SET
    attendee_user_id = EXCLUDED.attendee_user_id,
    attendee_person_public_id = EXCLUDED.attendee_person_public_id,
    attendee_name = EXCLUDED.attendee_name,
    attendee_type = EXCLUDED.attendee_type,
    response_status = EXCLUDED.response_status,
    responded_at = EXCLUDED.responded_at,
    updated_at = CURRENT_TIMESTAMP;

-- Replace the old synthetic member{id}@sk.com attendees with directory identities.
DELETE FROM cal_event_attendees attendee
 USING cal_events event
 WHERE attendee.event_id = event.event_id
   AND attendee.tenant_id = event.tenant_id
   AND attendee.attendee_email ~ '^member[0-9]+@sk[.]com$';

WITH tenant AS (
    SELECT tenant_id
      FROM sys_service_tenants
     WHERE tenant_key = 'default'
), company_events AS (
    SELECT event.event_id, event.tenant_id, event.organizer_user_id,
           seed.seed_key
      FROM tenant
     CROSS JOIN (VALUES ('townhall'), ('learning')) seed(seed_key)
      JOIN cal_events event
        ON event.event_id = md5(
            'calendar:company-event:' || tenant.tenant_id || ':'
                || seed.seed_key)::uuid
), ranked_members AS (
    SELECT member.*,
           ROW_NUMBER() OVER (ORDER BY member.user_id)::integer AS seed_rank
      FROM tmp_cal_skax_members member
)
INSERT INTO cal_event_attendees (
    attendee_id, tenant_id, event_id, attendee_user_id,
    attendee_person_public_id, attendee_email, attendee_name,
    attendee_type, response_status, responded_at)
SELECT md5(
           'calendar:attendee:company:' || event.event_id || ':'
               || member.user_id)::uuid,
       event.tenant_id, event.event_id, member.user_id,
       member.person_public_id, member.email, member.display_name,
       CASE event.seed_key WHEN 'townhall' THEN 'REQUIRED' ELSE 'OPTIONAL' END,
       CASE MOD(member.seed_rank + LENGTH(event.seed_key), 7)
           WHEN 0 THEN 'NEEDS_ACTION'
           WHEN 1 THEN 'TENTATIVE'
           WHEN 2 THEN 'DECLINED'
           ELSE 'ACCEPTED'
       END,
       CASE MOD(member.seed_rank + LENGTH(event.seed_key), 7)
           WHEN 0 THEN NULL
           ELSE CURRENT_TIMESTAMP
               - make_interval(hours => 4 + MOD(member.seed_rank, 48))
       END
  FROM company_events event
 CROSS JOIN ranked_members member
 WHERE member.user_id <> event.organizer_user_id
ON CONFLICT (event_id, attendee_email) DO UPDATE SET
    attendee_user_id = EXCLUDED.attendee_user_id,
    attendee_person_public_id = EXCLUDED.attendee_person_public_id,
    attendee_name = EXCLUDED.attendee_name,
    attendee_type = EXCLUDED.attendee_type,
    response_status = EXCLUDED.response_status,
    responded_at = EXCLUDED.responded_at,
    updated_at = CURRENT_TIMESTAMP;

WITH tenant AS (
    SELECT tenant_id
      FROM sys_service_tenants
     WHERE tenant_key = 'default'
), ranked_members AS (
    SELECT member.*,
           ROW_NUMBER() OVER (ORDER BY member.user_id)::integer AS seed_rank
      FROM tmp_cal_skax_members member
), selected AS (
    SELECT tenant.tenant_id, calendar.calendar_id, member.*,
           date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + INTERVAL '9 days' AS starts_at_local
      FROM ranked_members member
     CROSS JOIN tenant
      JOIN cal_calendars calendar
        ON calendar.tenant_id = tenant.tenant_id
       AND calendar.calendar_key = 'personal-' || member.user_id
     WHERE MOD(member.seed_rank, 11) = 0
        OR member.user_id = 38
)
INSERT INTO cal_events (
    event_id, tenant_id, calendar_id, organizer_user_id,
    organizer_person_public_id, organizer_name, organizer_email,
    title, description, event_type, starts_at, ends_at, time_zone,
    all_day, status, visibility, recurrence_pattern, recurrence_interval,
    response_required, source_type, source_ref, idempotency_key,
    created_by, updated_by)
SELECT md5(
           'calendar:event:' || selected.tenant_id || ':'
               || selected.user_id || ':absence-v1')::uuid,
       selected.tenant_id, selected.calendar_id, selected.user_id,
       selected.person_public_id, selected.display_name, selected.email,
       (ARRAY['연차', '개인 휴무', '재충전 휴가'])[
           1 + MOD(selected.seed_rank, 3)],
       '인사 시스템의 부재 계획을 캘린더 가용 시간에 반영한 일정입니다.',
       'OUT_OF_OFFICE',
       selected.starts_at_local AT TIME ZONE 'Asia/Seoul',
       (selected.starts_at_local + INTERVAL '1 day')
           AT TIME ZONE 'Asia/Seoul',
       'Asia/Seoul', TRUE, 'CONFIRMED', 'PRIVATE', 'NONE', 1,
       FALSE, 'HRIS',
       'seed:skax:absence-v1:' || selected.user_id,
       md5(
           'calendar:idempotency:' || selected.tenant_id || ':'
               || selected.user_id || ':absence-v1')::uuid,
       selected.user_id, selected.user_id
  FROM selected
ON CONFLICT (event_id) DO UPDATE SET
    organizer_person_public_id = EXCLUDED.organizer_person_public_id,
    organizer_name = EXCLUDED.organizer_name,
    organizer_email = EXCLUDED.organizer_email,
    title = EXCLUDED.title,
    description = EXCLUDED.description,
    starts_at = EXCLUDED.starts_at,
    ends_at = EXCLUDED.ends_at,
    source_ref = EXCLUDED.source_ref,
    version = cal_events.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

WITH tenant AS (
    SELECT tenant_id
      FROM sys_service_tenants
     WHERE tenant_key = 'default'
), ranked_members AS (
    SELECT member.*,
           ROW_NUMBER() OVER (ORDER BY member.user_id)::integer AS seed_rank
      FROM tmp_cal_skax_members member
), selected AS (
    SELECT tenant.tenant_id, calendar.calendar_id, member.*,
           date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + INTERVAL '7 days 08:30' AS starts_at_local
      FROM ranked_members member
     CROSS JOIN tenant
      JOIN cal_calendars calendar
        ON calendar.tenant_id = tenant.tenant_id
       AND calendar.calendar_key = 'personal-' || member.user_id
     WHERE MOD(member.seed_rank, 5) = 0
)
INSERT INTO cal_events (
    event_id, tenant_id, calendar_id, organizer_user_id,
    organizer_person_public_id, organizer_name, organizer_email,
    title, description, event_type, starts_at, ends_at, time_zone,
    all_day, status, visibility, recurrence_pattern, recurrence_interval,
    response_required, source_type, source_ref, idempotency_key,
    created_by, updated_by)
SELECT md5(
           'calendar:event:' || selected.tenant_id || ':'
               || selected.user_id || ':reminder-v1')::uuid,
       selected.tenant_id, selected.calendar_id, selected.user_id,
       selected.person_public_id, selected.display_name, selected.email,
       (ARRAY[
           '주간 업무 입력 마감',
           '고객 피드백 확인',
           '성과 기록 업데이트',
           '필수 학습 완료 확인'
       ])[1 + MOD(selected.seed_rank, 4)],
       '놓치기 쉬운 실행 항목을 업무 시작 전에 확인합니다.',
       'REMINDER',
       selected.starts_at_local AT TIME ZONE 'Asia/Seoul',
       (selected.starts_at_local + INTERVAL '20 minutes')
           AT TIME ZONE 'Asia/Seoul',
       'Asia/Seoul', FALSE, 'CONFIRMED', 'PRIVATE', 'NONE', 1,
       FALSE, 'NATIVE',
       'seed:skax:reminder-v1:' || selected.user_id,
       md5(
           'calendar:idempotency:' || selected.tenant_id || ':'
               || selected.user_id || ':reminder-v1')::uuid,
       selected.user_id, selected.user_id
  FROM selected
ON CONFLICT (event_id) DO UPDATE SET
    organizer_person_public_id = EXCLUDED.organizer_person_public_id,
    organizer_name = EXCLUDED.organizer_name,
    organizer_email = EXCLUDED.organizer_email,
    title = EXCLUDED.title,
    description = EXCLUDED.description,
    starts_at = EXCLUDED.starts_at,
    ends_at = EXCLUDED.ends_at,
    source_ref = EXCLUDED.source_ref,
    version = cal_events.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

CREATE TEMP TABLE tmp_cal_skax_room_assignments (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    person_public_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    seed_rank INTEGER NOT NULL,
    calendar_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    resource_name VARCHAR(160) NOT NULL,
    approval_required BOOLEAN NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL
) ON COMMIT DROP;

WITH tenant AS (
    SELECT tenant_id
      FROM sys_service_tenants
     WHERE tenant_key = 'default'
), ranked_members AS (
    SELECT member.*,
           ROW_NUMBER() OVER (ORDER BY member.user_id)::integer AS seed_rank,
           ROW_NUMBER() OVER (ORDER BY member.user_id) AS slot_rank
      FROM tmp_cal_skax_members member
), room_slots AS (
    SELECT resource.tenant_id, resource.resource_id,
           resource.resource_code, resource.name_ko AS resource_name,
           resource.approval_required,
           (
               date_trunc(
                   'week', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')
               + INTERVAL '7 days'
               + make_interval(days => day_offset)
               + make_interval(mins => 510 + slot_index * 30)
           ) AT TIME ZONE 'Asia/Seoul' AS starts_at
      FROM cal_resources resource
     CROSS JOIN (VALUES (0), (2), (4)) day(day_offset)
     CROSS JOIN generate_series(0, 19) slot(slot_index)
     WHERE resource.tenant_id = (SELECT tenant_id FROM tenant)
       AND resource.resource_type = 'ROOM'
       AND resource.lifecycle_state = 'AVAILABLE'
), available_slots AS (
    SELECT room_slot.*,
           ROW_NUMBER() OVER (
               ORDER BY room_slot.starts_at, room_slot.resource_code) AS slot_rank
      FROM room_slots room_slot
     WHERE NOT EXISTS (
         SELECT 1
           FROM cal_resource_bookings booking
          WHERE booking.tenant_id = room_slot.tenant_id
            AND booking.resource_id = room_slot.resource_id
            AND booking.booking_status IN ('PENDING', 'CONFIRMED')
            AND tstzrange(booking.starts_at, booking.ends_at, '[)')
                && tstzrange(
                    room_slot.starts_at,
                    room_slot.starts_at + INTERVAL '30 minutes',
                    '[)')
     )
)
INSERT INTO tmp_cal_skax_room_assignments (
    tenant_id, user_id, event_id, person_public_id, email, display_name,
    seed_rank, calendar_id, resource_id, resource_name, approval_required,
    starts_at, ends_at)
SELECT tenant.tenant_id, member.user_id,
       md5(
           'calendar:event:' || tenant.tenant_id || ':'
               || member.user_id || ':collaboration-room-v1')::uuid,
       member.person_public_id, member.email, member.display_name,
       member.seed_rank, calendar.calendar_id, slot.resource_id,
       slot.resource_name, slot.approval_required,
       slot.starts_at, slot.starts_at + INTERVAL '30 minutes'
  FROM ranked_members member
 CROSS JOIN tenant
  JOIN cal_calendars calendar
    ON calendar.tenant_id = tenant.tenant_id
   AND calendar.calendar_key = 'personal-' || member.user_id
  JOIN available_slots slot ON slot.slot_rank = member.slot_rank;

DO $$
BEGIN
    IF (SELECT COUNT(*) FROM tmp_cal_skax_room_assignments) <> 178 THEN
        RAISE EXCEPTION 'Insufficient conflict-free room slots for SKAX seed';
    END IF;
END
$$;

INSERT INTO cal_events (
    event_id, tenant_id, calendar_id, organizer_user_id,
    organizer_person_public_id, organizer_name, organizer_email,
    title, description, event_type, starts_at, ends_at, time_zone,
    all_day, location, status, visibility, recurrence_pattern,
    recurrence_interval, response_required, source_type, source_ref,
    idempotency_key, created_by, updated_by)
SELECT assignment.event_id, assignment.tenant_id, assignment.calendar_id,
       assignment.user_id, assignment.person_public_id,
       assignment.display_name, assignment.email,
       (ARRAY[
           '프로젝트 협업 세션',
           '고객 과제 워킹 세션',
           '설계 검토 미팅',
           '딜리버리 협업 회의'
       ])[1 + MOD(assignment.seed_rank, 4)],
       '실행 결정을 내리고 담당자와 다음 행동을 확정하는 협업 일정입니다.',
       'MEETING', assignment.starts_at, assignment.ends_at,
       'Asia/Seoul', FALSE, assignment.resource_name, 'CONFIRMED',
       'DEFAULT', 'NONE', 1, TRUE, 'NATIVE',
       'seed:skax:collaboration-room-v1:' || assignment.user_id,
       md5(
           'calendar:idempotency:' || assignment.tenant_id || ':'
               || assignment.user_id || ':collaboration-room-v1')::uuid,
       assignment.user_id, assignment.user_id
  FROM tmp_cal_skax_room_assignments assignment
ON CONFLICT (event_id) DO UPDATE SET
    organizer_person_public_id = EXCLUDED.organizer_person_public_id,
    organizer_name = EXCLUDED.organizer_name,
    organizer_email = EXCLUDED.organizer_email,
    title = EXCLUDED.title,
    description = EXCLUDED.description,
    starts_at = EXCLUDED.starts_at,
    ends_at = EXCLUDED.ends_at,
    location = EXCLUDED.location,
    source_ref = EXCLUDED.source_ref,
    version = cal_events.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO cal_resource_bookings (
    booking_id, tenant_id, event_id, resource_id, starts_at, ends_at,
    booking_status, requested_by, created_by, updated_by)
SELECT md5(
           'calendar:booking:' || assignment.event_id || ':'
               || assignment.resource_id)::uuid,
       assignment.tenant_id, assignment.event_id, assignment.resource_id,
       assignment.starts_at, assignment.ends_at,
       CASE
           WHEN assignment.approval_required THEN 'PENDING'
           ELSE 'CONFIRMED'
       END,
       assignment.user_id, assignment.user_id, assignment.user_id
  FROM tmp_cal_skax_room_assignments assignment
ON CONFLICT (event_id, resource_id) DO UPDATE SET
    starts_at = EXCLUDED.starts_at,
    ends_at = EXCLUDED.ends_at,
    requested_by = EXCLUDED.requested_by,
    version = cal_resource_bookings.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO cal_event_attendees (
    attendee_id, tenant_id, event_id, attendee_user_id,
    attendee_person_public_id, attendee_email, attendee_name,
    attendee_type, response_status, responded_at)
SELECT md5(
           'calendar:attendee:room:' || assignment.event_id || ':'
               || peer.peer_user_id)::uuid,
       assignment.tenant_id, assignment.event_id, peer.peer_user_id,
       peer.peer_person_public_id, peer.peer_email, peer.peer_name,
       'REQUIRED',
       CASE MOD(assignment.seed_rank, 4)
           WHEN 0 THEN 'NEEDS_ACTION'
           WHEN 1 THEN 'TENTATIVE'
           ELSE 'ACCEPTED'
       END,
       CASE MOD(assignment.seed_rank, 4)
           WHEN 0 THEN NULL
           ELSE CURRENT_TIMESTAMP
               - make_interval(hours => 1 + MOD(assignment.seed_rank, 24))
       END
  FROM tmp_cal_skax_room_assignments assignment
  JOIN tmp_cal_skax_peers peer
    ON peer.organizer_user_id = assignment.user_id
ON CONFLICT (event_id, attendee_email) DO UPDATE SET
    attendee_user_id = EXCLUDED.attendee_user_id,
    attendee_person_public_id = EXCLUDED.attendee_person_public_id,
    attendee_name = EXCLUDED.attendee_name,
    attendee_type = EXCLUDED.attendee_type,
    response_status = EXCLUDED.response_status,
    responded_at = EXCLUDED.responded_at,
    updated_at = CURRENT_TIMESTAMP;

DO $$
DECLARE
    missing_count INTEGER;
BEGIN
    SELECT COUNT(*)
      INTO missing_count
      FROM tmp_cal_skax_members member
     WHERE NOT EXISTS (
         SELECT 1
           FROM sys_service_tenants tenant
           JOIN cal_calendars calendar
             ON calendar.tenant_id = tenant.tenant_id
            AND calendar.calendar_key = 'personal-' || member.user_id
            AND calendar.owner_user_id = member.user_id
            AND calendar.lifecycle_state = 'ACTIVE'
          WHERE tenant.tenant_key = 'default'
     );
    IF missing_count <> 0 THEN
        RAISE EXCEPTION '% SKAX members lack a personal calendar', missing_count;
    END IF;

    SELECT COUNT(*)
      INTO missing_count
      FROM tmp_cal_skax_members member
     WHERE NOT EXISTS (
         SELECT 1
           FROM sys_service_tenants tenant
           JOIN cal_events event
             ON event.tenant_id = tenant.tenant_id
            AND event.organizer_user_id = member.user_id
            AND event.status <> 'CANCELLED'
          WHERE tenant.tenant_key = 'default'
          GROUP BY event.organizer_user_id
         HAVING COUNT(*) >= 6
     );
    IF missing_count <> 0 THEN
        RAISE EXCEPTION '% SKAX members lack complete schedule data', missing_count;
    END IF;

    SELECT COUNT(*)
      INTO missing_count
      FROM tmp_cal_skax_members member
     WHERE NOT EXISTS (
         SELECT 1
           FROM sys_service_tenants tenant
           JOIN cal_identity_links identity_link
             ON identity_link.tenant_id = tenant.tenant_id
            AND identity_link.user_id = member.user_id
            AND identity_link.person_public_id = member.person_public_id
          WHERE tenant.tenant_key = 'default'
     );
    IF missing_count <> 0 THEN
        RAISE EXCEPTION '% SKAX members lack calendar identity links', missing_count;
    END IF;

    IF (SELECT COUNT(*) FROM tmp_cal_skax_room_assignments assignment
         JOIN cal_resource_bookings booking
           ON booking.event_id = assignment.event_id
          AND booking.resource_id = assignment.resource_id
        WHERE booking.booking_status IN ('PENDING', 'CONFIRMED')) <> 178 THEN
        RAISE EXCEPTION 'SKAX room booking seed coverage is incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM cal_event_attendees
         WHERE attendee_email ~ '^member[0-9]+@sk[.]com$'
    ) THEN
        RAISE EXCEPTION 'Legacy placeholder attendees remain';
    END IF;
END
$$;

COMMENT ON TABLE cal_identity_links IS
    'IAM-to-people identity bridge; SKAX development data is pre-projected and delivery environments synchronize it from authoritative systems.';
