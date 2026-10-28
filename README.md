# Fashion Commerce Backend

Backend cho nền tảng thương mại điện tử thời trang, được xây dựng theo kiến trúc modular monolith.

## Trạng thái hiện tại

Repo hiện đã hoàn thành nền tảng cơ sở dữ liệu, xác thực/phân quyền và vòng đời seller/shop.

Đã triển khai:

- Đăng ký, đăng nhập và đăng xuất.
- Cấp mới và xoay vòng refresh token.
- Lấy thông tin người dùng hiện tại.
- Phân quyền theo phạm vi platform và shop.
- Kiểm tra trạng thái tài khoản và quyền từ cơ sở dữ liệu.
- Kiểm tra cấu trúc schema cần thiết khi ứng dụng khởi động.
- Đăng ký và kiểm duyệt hồ sơ seller.
- Tạo, cập nhật, đóng và kiểm duyệt shop.
- Mời nhân viên, chấp nhận/từ chối lời mời và quản lý role trong shop.
- Xóa mềm nhân viên và chuyển nhượng quyền sở hữu shop.
- Tạo sản phẩm với SKU, giá, ảnh và tồn kho; seller đăng bán ngay không cần chờ duyệt.
- Báo cáo sản phẩm và hậu kiểm bằng các action cảnh cáo, ẩn, gỡ, khôi phục hoặc bác báo cáo.
- Ghi audit log cho các thao tác seller/shop nhạy cảm.
- Unit test, security test và bộ integration test PostgreSQL tùy chọn.

Các module cart, order, payment và shipping hiện mới có schema cơ sở dữ liệu, chưa có API nghiệp vụ.

## Công nghệ

- Java 21
- Spring Boot 4.1.1
- Spring Security 7.1.1
- Spring Data JPA
- PostgreSQL 18
- Flyway
- Maven Wrapper

## Cấu trúc chính

```text
src/main/java/com/fashioncommerce/fashioncommercebackend
├── auth             # Đăng ký, đăng nhập, refresh token và đăng xuất
├── authorization    # Phân quyền platform và shop
├── common           # Xử lý lỗi và kiểm tra schema
├── product          # Catalog, SKU, tồn kho và hậu kiểm sản phẩm
├── security         # JWT, Spring Security và CORS
└── user             # User, credential, session và thông tin cá nhân

src/main/resources
├── application.yaml
└── db/migration     # Flyway migrations
```

## Cơ sở dữ liệu

Repo có 13 migration, tạo tổng cộng 58 bảng:

| Migration | Phạm vi |
|---|---|
| `V1` | Identity, RBAC, seller và shop |
| `V1.1` | Seed feature, role và permission |
| `V2` | Catalog và inventory |
| `V3` | Cart, checkout, order và reservation |
| `V4` | Payment, shipping, return và refund |
| `V5` | Voucher, review, moderation và notification |
| `V6` | Vòng đời seller/shop, nhân sự và chuyển nhượng ownership |
| `V7` | Onboarding seller nhiều bước, địa chỉ lấy hàng, vận chuyển, thuế, CCCD và chính sách |
| `V8` | Catalog phiên bản chính sách seller và trạng thái chờ chấp nhận chính sách |
| `V9` | Gộp thuế, CCCD và chính sách thành một hồ sơ với một lần moderator duyệt |
| `V10` | Đăng bán sản phẩm ngay, moderation case và hậu kiểm sản phẩm |
| `V11` | Bổ sung action bác báo cáo sản phẩm theo migration tiến tới |
| `V12` | Thu gọn catalog đang hoạt động còn Áo, Quần và Váy & Đầm |

Flyway bị tắt mặc định để tránh vô tình thay đổi sai database. Chỉ bật migration trên database development hoặc test đã được xác nhận.

## Cấu hình môi trường

Sao chép `.env.example` thành `.env`, sau đó điền các biến sau:

```dotenv
DB_HOST=
DB_NAME=
DB_USERNAME=
DB_PASSWORD=
DB_MIGRATIONS_ENABLED=false

JWT_SECRET_BASE64=
JWT_ISSUER=fashion-commerce
JWT_ACCESS_TTL=15m
REFRESH_TOKEN_TTL=14d

CORS_ALLOWED_ORIGINS=
```

`JWT_SECRET_BASE64` phải là chuỗi Base64 của ít nhất 32 byte ngẫu nhiên. Có thể tạo khóa trong PowerShell:

```powershell
$keyBytes = [byte[]]::new(32)
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($keyBytes)
$random.Dispose()
[Convert]::ToBase64String($keyBytes)
```

Không commit file `.env` hoặc chia sẻ JWT secret.

## Chạy ứng dụng

Yêu cầu JDK 21 và một PostgreSQL database đã có schema phù hợp.

```powershell
.\mvnw.cmd spring-boot:run
```

Migration mặc định không tự chạy. Nếu dùng database development riêng và muốn Flyway áp dụng migration, đặt:

```dotenv
DB_MIGRATIONS_ENABLED=true
```

Không bật migration trên database production nếu chưa kiểm tra kỹ cấu hình kết nối.

## API hiện có

| Method | Endpoint | Quyền truy cập | Kết quả |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Public | Tạo tài khoản |
| `POST` | `/api/v1/auth/login` | Public | Trả về access token và refresh token |
| `POST` | `/api/v1/auth/refresh` | Refresh token hợp lệ | Xoay vòng token |
| `POST` | `/api/v1/auth/logout` | Bearer token | Thu hồi refresh session |
| `GET` | `/api/v1/users/me` | Bearer token và quyền `account.read_own` | Thông tin người dùng hiện tại |

### Seller

| Method | Endpoint | Mô tả |
|---|---|---|
| `POST` | `/api/v1/seller/onboarding` | Bước 1: tạo gian hàng nháp |
| `GET` | `/api/v1/seller/onboarding/me` | Xem onboarding đang thực hiện |
| `GET` | `/api/v1/seller/onboarding/{id}` | Xem đầy đủ tiến độ onboarding |
| `PUT` | `/api/v1/seller/onboarding/{id}/shop` | Cập nhật tên, email, điện thoại, avatar, mô tả và ảnh bìa |
| `PUT` | `/api/v1/seller/onboarding/{id}/pickup-address` | Bước 2: thiết lập địa chỉ lấy hàng |
| `GET` | `/api/v1/seller/onboarding/{id}/shipping-providers` | Danh sách hãng vận chuyển khả dụng |
| `PUT` | `/api/v1/seller/onboarding/{id}/shipping` | Bước 3: bật/tắt hãng vận chuyển và chọn địa chỉ lấy hàng |
| `PUT` | `/api/v1/seller/onboarding/{id}/tax` | Bước 4: lưu thông tin thuế |
| `PUT` | `/api/v1/seller/onboarding/{id}/identity` | Lưu thông tin CCCD vào hồ sơ nháp |
| `GET` | `/api/v1/seller/onboarding/{id}/policies` | Danh sách chính sách đang hiệu lực và trạng thái chấp nhận |
| `POST` | `/api/v1/seller/onboarding/{id}/policy-acceptances` | Đồng ý một phiên bản chính sách seller |
| `POST` | `/api/v1/seller/onboarding/{id}/complete` | Hoàn tất và gửi toàn bộ hồ sơ để duyệt một lần |
| `GET` | `/api/v1/admin/seller-onboardings/reviews` | Hàng đợi hồ sơ seller tổng hợp |
| `POST` | `/api/v1/admin/seller-onboardings/{id}/decision` | Moderator duyệt hoặc từ chối toàn bộ hồ sơ |
| `GET` | `/api/v1/seller/applications` | Xem lịch sử seller application đã tạo khi hoàn tất |

Ba loại hình thuế hiện có là `INDIVIDUAL`, `HOUSEHOLD_BUSINESS` và `ENTERPRISE`. Shop ở trạng thái
`DRAFT` trong lúc người bán khai báo shop, địa chỉ, vận chuyển, thuế, CCCD và chấp nhận chính sách.
API hoàn tất chuyển toàn bộ gói sang `PENDING_REVIEW`. Moderator chỉ quyết định một lần; khi approve,
seller application thành `APPROVED` và shop chuyển sang `ACTIVE` trong cùng transaction.

### Sản phẩm và hậu kiểm

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/api/v1/catalog/categories` | Danh mục đang hoạt động, không yêu cầu đăng nhập |
| `GET` | `/api/v1/catalog/brands` | Thương hiệu đang hoạt động, không yêu cầu đăng nhập |
| `POST` | `/api/v1/shops/{shopId}/products` | Tạo sản phẩm nháp cùng option, SKU, giá, ảnh và tồn kho ban đầu |
| `GET` | `/api/v1/shops/{shopId}/products` | Danh sách sản phẩm của shop, gồm cả sản phẩm chưa công khai |
| `GET` | `/api/v1/shops/{shopId}/products/{productId}` | Chi tiết sản phẩm dành cho shop |
| `PATCH` | `/api/v1/shops/{shopId}/products/{productId}` | Cập nhật thông tin mô tả sản phẩm |
| `PUT` | `/api/v1/shops/{shopId}/products/variants/{variantId}/inventory` | Điều chỉnh tồn kho SKU và ghi inventory movement |
| `POST` | `/api/v1/shops/{shopId}/products/{productId}/publish` | Đăng bán ngay, không qua bước duyệt |
| `POST` | `/api/v1/shops/{shopId}/products/{productId}/unpublish` | Seller chủ động ngừng bán |
| `GET` | `/api/v1/products` | Danh sách sản phẩm `ACTIVE` công khai |
| `GET` | `/api/v1/products/{productId}` | Chi tiết sản phẩm `ACTIVE` công khai |
| `POST` | `/api/v1/products/{productId}/reports` | Người dùng đăng nhập báo cáo sản phẩm đang bán |
| `GET` | `/api/v1/admin/product-moderation/cases` | Hàng đợi report dành cho moderator |
| `GET` | `/api/v1/admin/product-moderation/cases/{caseId}` | Chi tiết moderation case |
| `POST` | `/api/v1/admin/product-moderation/products/{productId}/decision` | `WARN`, `HIDE`, `REMOVE`, `RESTORE` hoặc `DISMISS` |

Sản phẩm mới ở trạng thái `DRAFT`. Khi có ít nhất một SKU active, bản ghi tồn kho và một ảnh chính,
seller có thể publish để chuyển trực tiếp sang `ACTIVE`. `HIDDEN` và `REMOVED` không xuất hiện ở API công khai;
`RESTORE` đưa sản phẩm trở lại `ACTIVE`. Mọi quyết định hậu kiểm đều được ghi audit, moderation action và gửi thông báo cho owner.

### Shop và nhân viên

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/api/v1/shops/mine` | Danh sách shop đang tham gia |
| `GET/PATCH/DELETE` | `/api/v1/shops/{shopId}` | Xem, cập nhật hoặc đóng shop |
| `GET/PUT` | `/api/v1/shops/{shopId}/settings` | Xem hoặc cập nhật cài đặt shop |
| `GET` | `/api/v1/shops/{shopId}/members` | Danh sách thành viên |
| `GET` | `/api/v1/shops/{shopId}/assignable-roles` | Danh sách role có thể gán cho nhân viên |
| `POST` | `/api/v1/shops/{shopId}/members/invitations` | Mời tài khoản đã đăng ký |
| `PUT` | `/api/v1/shops/{shopId}/members/{memberId}/roles` | Thay đổi role nhân viên |
| `DELETE` | `/api/v1/shops/{shopId}/members/{memberId}` | Vô hiệu hóa nhân viên |
| `DELETE` | `/api/v1/shops/{shopId}/members/me` | Nhân viên tự rời shop |
| `POST` | `/api/v1/shops/{shopId}/ownership-transfers` | Tạo đề nghị chuyển nhượng shop |
| `GET` | `/api/v1/shops/{shopId}/ownership-transfers` | Xem lịch sử chuyển nhượng của shop |
| `GET` | `/api/v1/shop-ownership-transfers` | Xem đề nghị chuyển nhượng được nhận |
| `POST` | `/api/v1/shop-ownership-transfers/{id}/accept` | Chấp nhận chuyển nhượng |
| `POST` | `/api/v1/shop-ownership-transfers/{id}/reject` | Từ chối chuyển nhượng |
| `DELETE` | `/api/v1/shop-ownership-transfers/{id}` | Owner hủy đề nghị đang chờ |
| `GET` | `/api/v1/shop-invitations` | Xem lời mời của người dùng hiện tại |
| `POST` | `/api/v1/shop-invitations/{memberId}/accept` | Chấp nhận lời mời |
| `POST` | `/api/v1/shop-invitations/{memberId}/decline` | Từ chối lời mời |
| `GET` | `/api/v1/admin/shops` | Danh sách shop cho moderator |
| `POST` | `/api/v1/admin/shops/{shopId}/moderation` | Suspend, reactivate hoặc close shop |

Role nhân viên có thể gán qua API gồm `SHOP_MANAGER`, `SHOP_CATALOG`, `SHOP_ORDERS`, `SHOP_SUPPORT` và `SHOP_FINANCE`.
`SHOP_OWNER` không thể gán trực tiếp; role này chỉ thay đổi qua quy trình chuyển nhượng ownership.

Quy tắc chính:

- Shop được tạo dạng `DRAFT` ngay khi bắt đầu onboarding và chỉ chuyển `ACTIVE` sau khi hoàn tất toàn bộ quy trình.
- Mỗi user chỉ được sở hữu một shop tại một thời điểm.
- Lời mời nhân viên chỉ gửi tới tài khoản đã đăng ký và đang hoạt động.
- Xóa nhân viên là vô hiệu hóa mềm để giữ lịch sử và audit log.
- Owner không thể tự rời hoặc bị xóa khỏi shop; phải chuyển nhượng hoặc đóng shop trước.
- Đề nghị chuyển nhượng hết hạn sau 7 ngày và chỉ hoàn tất khi người nhận chấp nhận.
- Người nhận ownership phải là thành viên active, có hồ sơ seller đã duyệt và chưa sở hữu shop khác.
- Các quyết định seller/shop, thay đổi nhân sự và chuyển ownership đều được ghi audit log và tạo thông báo phù hợp.

### Thông báo

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/api/v1/notifications` | Xem thông báo, hỗ trợ lọc chưa đọc |
| `GET` | `/api/v1/notifications/unread-count` | Đếm thông báo chưa đọc |
| `PATCH` | `/api/v1/notifications/{id}/read` | Đánh dấu một thông báo đã đọc |
| `POST` | `/api/v1/notifications/read-all` | Đánh dấu tất cả đã đọc |

Access token được gửi qua header:

```http
Authorization: Bearer <access-token>
```

Refresh token hiện được gửi trong JSON body. API chưa sử dụng cookie authentication và không tạo HTTP session.

## Chính sách xác thực

- Mật khẩu được băm bằng BCrypt với cost 12.
- Access token dùng JWT HS256 và mặc định hết hạn sau 15 phút.
- Refresh token là token ngẫu nhiên, được lưu trong database dưới dạng SHA-256.
- Refresh token được thay mới sau mỗi lần refresh và không thể tái sử dụng token cũ.
- Logout thu hồi refresh session; access token đã cấp vẫn hợp lệ đến khi hết hạn.
- Tài khoản phải ở trạng thái `ACTIVE` khi sử dụng access token.
- Tài khoản chưa xác minh email hiện vẫn có thể đăng nhập.
- CORS mặc định không cho phép origin nào nếu `CORS_ALLOWED_ORIGINS` để trống.

## Chạy test

Unit test và security test không yêu cầu kết nối database:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

Integration test PostgreSQL chỉ chạy khi cung cấp đầy đủ biến môi trường cho một database test độc lập:

```powershell
$env:FC_RUN_POSTGRES_TESTS = 'ISOLATED_DATABASE_ONLY'
$env:TEST_DB_HOST = '<test-host>'
$env:TEST_DB_NAME = '<test-database>'
$env:TEST_DB_USERNAME = '<test-user>'
$env:TEST_DB_PASSWORD = '<test-password>'
.\mvnw.cmd '-Dtest=FashionCommerceBackendApplicationTests' test
```

Không dùng database production cho integration test. Bộ test có thể áp dụng toàn bộ migration và tạo dữ liệu kiểm thử.

## Chưa triển khai

- Xác minh email và quên mật khẩu.
- OAuth, đăng nhập mạng xã hội và 2FA.
- API quản trị role/permission.
- Cart và checkout.
- Order, payment và shipping.
- Return và refund.
- Voucher và review.

## Lưu ý bảo mật

- Không ghi password, token hoặc dữ liệu nhạy cảm vào log.
- Không bật Hibernate bind-parameter logging trên môi trường thật.
- Nếu chuyển refresh token sang HttpOnly cookie, cần thiết kế lại chính sách CSRF và SameSite trước khi bật cookie authentication.
- Luôn kiểm tra endpoint database trước khi bật Flyway hoặc chạy integration test.
