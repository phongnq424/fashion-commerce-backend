# Fashion Commerce Backend - Frontend API Contract

Tài liệu này phản ánh các controller đang tồn tại trong source code. API tạo order, payment, shipping,
voucher, review, return và refund chưa có controller nghiệp vụ.

## 1. Quy ước chung

- Base path: `/api/v1`
- Trừ các API được đánh dấu `Public`, tất cả request phải gửi `Authorization: Bearer <accessToken>`.
- Request JSON gửi `Content-Type: application/json`.
- UUID và thời gian được trả dưới dạng string; thời gian dùng ISO-8601 UTC.
- Các field `*Minor` là số nguyên tiền VND. Ví dụ `199000` tương ứng `199.000đ`.
- `imageKey`, `logoKey`, `bannerKey`, `documentKey` là storage key, chưa phải public URL.
- Các response `204 No Content` không có body.

### Error response

```ts
interface ApiError {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
}
```

Validation body/query/path sai thường trả `400 REQUEST_INVALID`. Không có hoặc access token không hợp lệ trả
`401 AUTH_UNAUTHORIZED`; không đủ quyền trả `403 ACCESS_DENIED`.

### Pagination

```ts
interface SliceResponse<T> {
  items: T[];
  limit: number;
  offset: number;
  hasMore: boolean;
}

interface CursorPageResponse<T> {
  items: T[];
  limit: number;
  nextCursor: string | null;
  hasMore: boolean;
}
```

Offset pagination mặc định `limit=20&offset=0`. Public product dùng opaque cursor; frontend chỉ truyền lại
`nextCursor`, không tự parse hoặc chỉnh sửa cursor.

## 2. Auth và current user

| Method | Endpoint | Access | Request | Response |
|---|---|---|---|---|
| POST | `/auth/register` | Public | `RegisterRequest` | `201 UserResponse` |
| POST | `/auth/login` | Public | `LoginRequest` | `200 TokenResponse` |
| POST | `/auth/refresh` | Public | `RefreshRequest` | `200 TokenResponse` |
| POST | `/auth/logout` | Bearer | `RefreshRequest` | `204` |
| GET | `/users/me` | Bearer | - | `200 UserResponse` |

```ts
interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
  phone?: string | null;
}

interface LoginRequest { email: string; password: string }
interface RefreshRequest { refreshToken: string }

interface TokenResponse {
  tokenType: string;            // "Bearer"
  accessToken: string;
  expiresIn: number;            // seconds
  refreshToken: string;
  refreshExpiresAt: string;
}

interface UserResponse {
  id: string;
  email: string;
  displayName: string;
  status: string;
  emailVerifiedAt: string | null;
  roles: string[];
}
```

Logout thu hồi cả refresh-token session và access token hiện tại. Sau refresh thành công, frontend phải thay cả
access token và refresh token cũ bằng cặp token mới.

## 3. Buyer address

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/users/me/addresses` | - | `200 AddressResponse[]` |
| POST | `/users/me/addresses` | `CreateAddressRequest` | `201 AddressResponse` |
| PUT | `/users/me/addresses/{addressId}` | `UpdateAddressRequest` | `200 AddressResponse` |
| DELETE | `/users/me/addresses/{addressId}` | - | `204` |

`PUT` yêu cầu gửi đầy đủ payload:

```ts
interface AddressRequest {
  recipientName: string;
  recipientPhone: string;
  addressLine1: string;
  addressLine2?: string | null;
  ward?: string | null;
  district?: string | null;
  province: string;
  provinceCode?: string | null;
  postalCode?: string | null;
  countryCode: string;          // 2 ký tự viết hoa, ví dụ VN
  defaultAddress: boolean;
}

type CreateAddressRequest = AddressRequest;
type UpdateAddressRequest = AddressRequest;

interface AddressResponse extends AddressRequest {
  id: string;
  createdAt: string;
  updatedAt: string;
}
```

Địa chỉ đầu tiên tự động thành mặc định. Lỗi riêng: `404 ADDRESS_NOT_FOUND`,
`404 CHECKOUT_ADDRESS_NOT_FOUND`, `409 ADDRESS_DEFAULT_CONFLICT`.

## 4. Notifications

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/notifications?unreadOnly=false&limit=20&offset=0` | - | `200 SliceResponse<NotificationResponse>` |
| GET | `/notifications/unread-count` | - | `200 { unreadCount: number }` |
| PATCH | `/notifications/{notificationId}/read` | - | `200 NotificationResponse` |
| POST | `/notifications/read-all` | - | `204` |

```ts
interface NotificationResponse {
  id: string;
  type: string;
  title: string;
  body: string;
  payload: Record<string, unknown>;
  readAt: string | null;
  createdAt: string;
}
```

## 5. Seller onboarding

### Seller endpoints

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/seller/onboarding` | `ShopProfileRequest` | `201 OnboardingResponse` |
| GET | `/seller/onboarding/me` | - | `200 OnboardingResponse` |
| GET | `/seller/onboarding/{onboardingId}` | - | `200 OnboardingResponse` |
| PUT | `/seller/onboarding/{onboardingId}/shop` | `ShopProfileRequest` | `200 OnboardingResponse` |
| PUT | `/seller/onboarding/{onboardingId}/pickup-address` | `PickupAddressRequest` | `200 PickupAddressResponse` |
| GET | `/seller/onboarding/{onboardingId}/shipping-providers` | - | `200 ShippingProviderResponse[]` |
| PUT | `/seller/onboarding/{onboardingId}/shipping` | `ShippingConfigurationRequest` | `200 ShippingProviderResponse[]` |
| PUT | `/seller/onboarding/{onboardingId}/tax` | `TaxProfileRequest` | `200 TaxProfileResponse` |
| PUT | `/seller/onboarding/{onboardingId}/identity` | `IdentityVerificationRequest` | `200 OnboardingResponse` |
| GET | `/seller/onboarding/{onboardingId}/policies` | - | `200 PolicyVersionResponse[]` |
| POST | `/seller/onboarding/{onboardingId}/policy-acceptances` | `PolicyAcceptanceRequest` | `200 OnboardingResponse` |
| POST | `/seller/onboarding/{onboardingId}/complete` | - | `200 OnboardingResponse` |
| GET | `/seller/applications?limit=20&offset=0` | - | `200 SliceResponse<ApplicationSummaryResponse>` |
| GET | `/seller/applications/{applicationId}` | - | `200 ApplicationResponse` |

```ts
interface ShopProfileRequest {
  name: string;
  email: string;
  phone: string;
  avatarKey?: string | null;
  description?: string | null;
  coverKey?: string | null;
}

interface PickupAddressRequest {
  contactName: string;
  contactPhone: string;
  provinceCode?: string | null;
  provinceName: string;
  wardCode?: string | null;
  wardName: string;
  addressDetail: string;
  defaultPickup: boolean;
}

interface ShippingConfigurationRequest {
  pickupAddressId: string;
  providers: Array<{ providerCode: string; enabled: boolean }>;
}

type BusinessType = "INDIVIDUAL" | "HOUSEHOLD_BUSINESS" | "ENTERPRISE";

interface TaxProfileRequest {
  businessType: BusinessType;
  legalName?: string | null;
  businessName?: string | null;
  legalRepresentativeName?: string | null;
  taxCode: string;
  businessRegistrationNumber?: string | null;
  issuedDate?: string | null;            // YYYY-MM-DD
  issuedBy?: string | null;
  registeredAddress: {
    provinceCode?: string | null;
    provinceName: string;
    wardCode?: string | null;
    wardName: string;
    addressDetail: string;
  };
  invoiceEmail: string;
  registrationDocumentKey?: string | null;
}

interface IdentityVerificationRequest {
  legalName: string;
  documentNumberLast4: string;           // đúng 4 chữ số
  frontDocumentKey: string;
  backDocumentKey: string;
  selfieDocumentKey: string;
}

interface PolicyAcceptanceRequest {
  policyVersion: string;
  accepted: true;
}
```

```ts
interface ShopProfileResponse {
  shopId: string; name: string; slug: string; email: string; phone: string;
  avatarKey: string | null; description: string | null; coverKey: string | null; status: string;
}

interface PickupAddressResponse {
  id: string; contactName: string; contactPhone: string; provinceCode: string | null;
  provinceName: string; wardCode: string | null; wardName: string; addressDetail: string;
  defaultPickup: boolean; active: boolean;
}

interface ShippingProviderResponse {
  code: string; name: string; description: string | null; available: boolean;
  enabled: boolean; pickupAddressId: string | null;
}

interface TaxProfileResponse {
  businessType: BusinessType; legalName: string | null; businessName: string | null;
  legalRepresentativeName: string | null; taxCode: string; businessRegistrationNumber: string | null;
  issuedDate: string | null; issuedBy: string | null; registeredAddress: Record<string, unknown>;
  invoiceEmail: string; registrationDocumentKey: string | null; status: string;
  reviewedBy: string | null; reviewedAt: string | null; reviewNote: string | null;
}

interface IdentityVerificationResponse {
  documentType: string; legalName: string; documentNumberLast4: string;
  frontDocumentKey: string; backDocumentKey: string; selfieDocumentKey: string;
  status: string; reviewedBy: string | null; reviewedAt: string | null; reviewNote: string | null;
}

interface PolicyVersionResponse {
  version: string; title: string; required: boolean; effectiveAt: string; accepted: boolean;
}

interface OnboardingResponse {
  id: string; applicantUserId: string; status: string; shop: ShopProfileResponse;
  pickupAddresses: PickupAddressResponse[]; shippingProviders: ShippingProviderResponse[];
  taxProfile: TaxProfileResponse | null; identityVerification: IdentityVerificationResponse | null;
  acceptedPolicyVersions: string[]; submittedAt: string | null; reviewedBy: string | null;
  reviewedAt: string | null; reviewNote: string | null; completedAt: string | null;
  createdAt: string; updatedAt: string;
}

interface ApplicationResponse {
  id: string; applicantUserId: string; proposedShopName: string; contactEmail: string;
  contactPhone: string; legalName: string; legalIdentifierLast4: string | null;
  documentKey: string | null; status: string; reviewedBy: string | null;
  reviewedAt: string | null; reviewNote: string | null; createdAt: string; updatedAt: string;
}

interface ApplicationSummaryResponse {
  id: string; applicantUserId: string; proposedShopName: string; contactEmail: string;
  status: string; reviewedAt: string | null; createdAt: string; updatedAt: string;
}
```

### Admin seller endpoints

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/admin/seller-applications?status=&limit=20&offset=0` | - | `200 SliceResponse<ApplicationSummaryResponse>` |
| GET | `/admin/seller-applications/{applicationId}` | - | `200 ApplicationResponse` |
| GET | `/admin/seller-onboardings/reviews?status=PENDING_REVIEW&limit=20&offset=0` | - | `200 SliceResponse<OnboardingSummaryResponse>` |
| GET | `/admin/seller-onboardings/{onboardingId}` | - | `200 OnboardingResponse` |
| POST | `/admin/seller-onboardings/{onboardingId}/decision` | `ReviewRequest` | `200 OnboardingResponse` |

```ts
interface ReviewRequest {
  decision: "APPROVED" | "REJECTED";
  note?: string | null;
}

interface OnboardingSummaryResponse {
  id: string; applicantUserId: string; applicantEmail: string; applicantDisplayName: string;
  shopId: string; shopName: string; shopSlug: string; status: string;
  taxStatus: string | null; identityStatus: string | null;
  submittedAt: string | null; updatedAt: string;
}
```

## 6. Shop, staff và ownership

### Shop endpoints

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/shops/mine?limit=20&offset=0` | - | `200 SliceResponse<ShopSummaryResponse>` |
| GET | `/shops/{shopId}` | - | `200 ShopResponse` |
| PATCH | `/shops/{shopId}` | `UpdateShopRequest` | `200 ShopResponse` |
| DELETE | `/shops/{shopId}` | - | `204` |
| GET | `/shops/{shopId}/settings` | - | `200 SettingsResponse` |
| PUT | `/shops/{shopId}/settings` | `UpdateSettingsRequest` | `200 SettingsResponse` |
| GET | `/shops/{shopId}/members?limit=20&offset=0` | - | `200 SliceResponse<MemberResponse>` |
| GET | `/shops/{shopId}/assignable-roles` | - | `200 RoleResponse[]` |
| POST | `/shops/{shopId}/members/invitations` | `InviteMemberRequest` | `201 MemberResponse` |
| PUT | `/shops/{shopId}/members/{memberId}/roles` | `UpdateMemberRolesRequest` | `200 MemberResponse` |
| DELETE | `/shops/{shopId}/members/{memberId}` | - | `204` |
| DELETE | `/shops/{shopId}/members/me` | - | `204` |

```ts
interface UpdateShopRequest {
  name?: string;
  slug?: string;
  description?: string | null;
  logoKey?: string | null;
  bannerKey?: string | null;
  contactEmail?: string;
  contactPhone?: string;
  pickupAddress?: {
    contactName: string;
    contactPhone: string;
    addressLine1: string;
    addressLine2?: string | null;
    ward?: string | null;
    district?: string | null;
    province: string;
    provinceCode?: string | null;
    postalCode?: string | null;
    countryCode: string;
  };
}

interface UpdateSettingsRequest {
  handlingDays: number;
  returnWindowDays: number;
  defaultShippingFeeMinor: number;
  allowCod: boolean;
  autoAcceptOrders: boolean;
}

interface InviteMemberRequest { email: string; roles: string[] }
interface UpdateMemberRolesRequest { roles: string[] }
```

```ts
interface ShopResponse {
  id: string; ownerUserId: string; applicationId: string; name: string; slug: string;
  description: string | null; logoKey: string | null; bannerKey: string | null;
  contactEmail: string; contactPhone: string; pickupAddress: Record<string, unknown>;
  status: string; activatedAt: string; suspendedAt: string | null; createdAt: string; updatedAt: string;
}

interface ShopSummaryResponse {
  id: string; ownerUserId: string; name: string; slug: string; logoKey: string | null;
  status: string; activatedAt: string | null; suspendedAt: string | null;
  createdAt: string; updatedAt: string;
}

interface SettingsResponse {
  shopId: string; handlingDays: number; returnWindowDays: number;
  defaultShippingFeeMinor: number; allowCod: boolean; autoAcceptOrders: boolean;
}

interface MemberResponse {
  memberId: string; userId: string; email: string; displayName: string; status: string;
  invitedBy: string | null; joinedAt: string | null; createdAt: string; roles: string[];
}

interface RoleResponse { code: string; name: string; description: string | null }

interface InvitationResponse {
  memberId: string; shopId: string; shopName: string; invitedBy: string | null;
  createdAt: string; roles: string[];
}

interface OwnershipTransferResponse {
  id: string; shopId: string; shopName: string; fromOwnerUserId: string; toUserId: string;
  keepPreviousOwnerAsManager: boolean; status: string; expiresAt: string;
  decidedAt: string | null; createdAt: string;
}
```

### Invitations

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/shop-invitations` | - | `200 InvitationResponse[]` |
| POST | `/shop-invitations/{memberId}/accept` | - | `200 MemberResponse` |
| POST | `/shop-invitations/{memberId}/decline` | - | `204` |

### Ownership transfers

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/shops/{shopId}/ownership-transfers` | `TransferOwnershipRequest` | `201 OwnershipTransferResponse` |
| GET | `/shops/{shopId}/ownership-transfers` | - | `200 OwnershipTransferResponse[]` |
| GET | `/shop-ownership-transfers` | - | `200 OwnershipTransferResponse[]` |
| POST | `/shop-ownership-transfers/{transferId}/accept` | - | `200 ShopResponse` |
| POST | `/shop-ownership-transfers/{transferId}/reject` | - | `204` |
| DELETE | `/shop-ownership-transfers/{transferId}` | - | `204` |

```ts
interface TransferOwnershipRequest {
  newOwnerUserId: string;
  keepPreviousOwnerAsManager: boolean;
}
```

### Admin shop moderation

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/admin/shops?status=&limit=20&offset=0` | - | `200 SliceResponse<ShopSummaryResponse>` |
| GET | `/admin/shops/{shopId}` | - | `200 ShopResponse` |
| POST | `/admin/shops/{shopId}/moderation` | `ModerateShopRequest` | `200 ShopResponse` |

```ts
interface ModerateShopRequest {
  action: "SUSPEND" | "REACTIVATE" | "CLOSE";
  reason: string;
}
```

## 7. Public catalog và products

Các GET catalog/product trong mục này là `Public`.

| Method | Endpoint | Access | Response |
|---|---|---|---|
| GET | `/catalog/categories` | Public | `200 CategoryResponse[]` |
| GET | `/catalog/brands` | Public | `200 BrandResponse[]` |
| GET | `/products` | Public | `200 CursorPageResponse<PublicProductSummaryResponse>` |
| GET | `/products/{productId}` | Public | `200 PublicProductResponse` |
| POST | `/products/{productId}/reports` | Bearer | `201 ModerationCaseResponse` |

Public search query:

```text
GET /products
  ?q=shirt
  &categoryId=<uuid>
  &brandId=<uuid>
  &gender=UNISEX
  &minPriceMinor=100000
  &maxPriceMinor=500000
  &inStock=true
  &sort=PRICE_ASC
  &cursor=<opaque cursor>
  &limit=20
```

- `gender`: `MALE | FEMALE | UNISEX | KIDS`
- `sort`: `NEWEST | PRICE_ASC | PRICE_DESC | NAME_ASC`

```ts
interface CategoryResponse { id: string; parentId: string | null; name: string; slug: string; description: string | null }
interface BrandResponse { id: string; name: string; slug: string; logoKey: string | null }

interface ReportProductRequest {
  reason: "COUNTERFEIT" | "PROHIBITED" | "MISLEADING" | "COPYRIGHT" | "INAPPROPRIATE" | "OTHER";
  description?: string | null;
  evidenceKeys?: string[] | null;
}
```

```ts
interface OptionValueResponse {
  id: string; code: string; label: string; hexColor: string | null; sortOrder: number;
}

interface OptionResponse {
  id: string; type: string; displayName: string; sortOrder: number; values: OptionValueResponse[];
}

interface ImageResponse {
  id: string; variantId: string | null; storageKey: string; altText: string | null;
  sortOrder: number; primary: boolean;
}

interface PublicVariantResponse {
  id: string; sellerSku: string; selections: Record<string, string>;
  priceMinor: number; compareAtPriceMinor: number | null; availableQuantity: number;
}

interface PublicProductResponse {
  id: string; shopId: string; categoryId: string; brandId: string | null;
  name: string; slug: string; description: string; material: string | null;
  fitType: string | null; targetGender: string; careInstructions: string | null;
  sizeChart: Record<string, unknown> | null; publishedAt: string;
  options: OptionResponse[]; variants: PublicVariantResponse[]; images: ImageResponse[];
}

interface PublicProductSummaryResponse {
  id: string; shopId: string; shopName: string; shopSlug: string;
  categoryId: string; brandId: string | null; name: string; slug: string; targetGender: string;
  primaryImageKey: string | null; minPriceMinor: number; maxPriceMinor: number;
  compareAtPriceMinor: number | null; variantCount: number; availableQuantity: number;
  publishedAt: string;
}
```

## 8. Seller product management

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/shops/{shopId}/products` | `CreateProductRequest` | `201 ProductResponse` |
| GET | `/shops/{shopId}/products?limit=20&offset=0` | - | `200 SliceResponse<ShopProductSummaryResponse>` |
| GET | `/shops/{shopId}/products/{productId}` | - | `200 ProductResponse` |
| PATCH | `/shops/{shopId}/products/{productId}` | `UpdateProductRequest` | `200 ProductResponse` |
| POST | `/shops/{shopId}/products/{productId}/publish?expectedVersion=N` | - | `200 ProductResponse` |
| POST | `/shops/{shopId}/products/{productId}/unpublish?expectedVersion=N` | - | `200 ProductResponse` |
| POST | `/shops/{shopId}/products/{productId}/variants` | `CreateVariantRequest` | `201 ProductResponse` |
| PATCH | `/shops/{shopId}/products/{productId}/variants/{variantId}` | `UpdateVariantRequest` | `200 ProductResponse` |
| PUT | `/shops/{shopId}/products/{productId}/variants/{variantId}/price` | `UpdateVariantPriceRequest` | `200 ProductResponse` |
| PUT | `/shops/{shopId}/products/{productId}/variants/{variantId}/status` | `UpdateVariantStatusRequest` | `200 ProductResponse` |
| POST | `/shops/{shopId}/products/{productId}/images` | `AddImageRequest` | `201 ProductResponse` |
| DELETE | `/shops/{shopId}/products/{productId}/images/{imageId}?expectedVersion=N` | - | `200 ProductResponse` |
| PUT | `/shops/{shopId}/products/{productId}/images/order` | `ReorderImagesRequest` | `200 ProductResponse` |
| PUT | `/shops/{shopId}/products/{productId}/images/{imageId}/primary` | `SetPrimaryImageRequest` | `200 ProductResponse` |
| PUT | `/shops/{shopId}/products/variants/{variantId}/inventory` | `InventoryRequest` | `200 ProductResponse` |

Product create payload:

```ts
interface CreateProductRequest {
  categoryId: string;
  brandId?: string | null;
  sellerProductCode?: string | null;
  name: string;
  description: string;
  material?: string | null;
  fitType?: string | null;
  targetGender: "MALE" | "FEMALE" | "UNISEX" | "KIDS";
  careInstructions?: string | null;
  sizeChart?: Record<string, unknown> | null;
  options: Array<{
    type: "SIZE" | "COLOR";
    displayName: string;
    sortOrder: number;
    values: Array<{ code: string; label: string; hexColor?: string | null; sortOrder: number }>;
  }>;
  variants: VariantCreateInput[];
  images: Array<{
    storageKey: string;
    altText?: string | null;
    sortOrder: number;
    primary: boolean;
    variantSellerSku?: string | null;
  }>;
}

interface VariantCreateInput {
  sellerSku: string;
  barcode?: string | null;
  selections: Record<string, string>;
  priceMinor: number;
  compareAtPriceMinor?: number | null;
  weightGrams: number;
  lengthMm?: number | null;
  widthMm?: number | null;
  heightMm?: number | null;
  initialStock: number;
  reorderPoint: number;
}
```

Mutation payloads:

```ts
interface UpdateProductRequest {
  expectedVersion: number;
  categoryId?: string;
  brandId?: string | null;
  sellerProductCode?: string | null;
  name?: string;
  description?: string;
  material?: string | null;
  fitType?: string | null;
  targetGender?: "MALE" | "FEMALE" | "UNISEX" | "KIDS";
  careInstructions?: string | null;
  sizeChart?: Record<string, unknown> | null;
}

interface CreateVariantRequest extends VariantCreateInput { expectedVersion: number }

interface UpdateVariantRequest {
  expectedVersion: number;
  sellerSku?: string;
  barcode?: string | null;
  weightGrams?: number;
  lengthMm?: number;
  widthMm?: number;
  heightMm?: number;
}

interface UpdateVariantPriceRequest {
  expectedVersion: number;
  priceMinor: number;
  compareAtPriceMinor?: number | null;
}

interface UpdateVariantStatusRequest {
  expectedVersion: number;
  status: "ACTIVE" | "INACTIVE";
}

interface AddImageRequest {
  expectedVersion: number;
  storageKey: string;
  altText?: string | null;
  sortOrder: number;
  primary: boolean;
  variantId?: string | null;
}

interface ReorderImagesRequest {
  expectedVersion: number;
  items: Array<{ imageId: string; sortOrder: number }>;
}

interface SetPrimaryImageRequest { expectedVersion: number }

interface InventoryRequest {
  quantityOnHand: number;
  reorderPoint: number;
  note?: string | null;
}
```

Mọi mutation metadata/SKU/image/publish phải dùng `ProductResponse.version` mới nhất. Version cũ trả
`409 PRODUCT_VERSION_CONFLICT`. Inventory có lock/version riêng nên `InventoryRequest` không cần product version.

```ts
interface VariantResponse {
  id: string; sellerSku: string; barcode: string | null; selections: Record<string, string>;
  priceMinor: number; compareAtPriceMinor: number | null; weightGrams: number;
  lengthMm: number | null; widthMm: number | null; heightMm: number | null; status: string;
  quantityOnHand: number; quantityReserved: number; availableQuantity: number; reorderPoint: number;
}

interface ProductResponse {
  id: string; shopId: string; categoryId: string; brandId: string | null;
  sellerProductCode: string | null; name: string; slug: string; description: string;
  material: string | null; fitType: string | null; targetGender: string;
  careInstructions: string | null; sizeChart: Record<string, unknown> | null;
  version: number; status: string; publishedAt: string | null;
  options: OptionResponse[]; variants: VariantResponse[]; images: ImageResponse[];
  createdAt: string; updatedAt: string;
}

interface ShopProductSummaryResponse {
  id: string; shopId: string; sellerProductCode: string | null; name: string; slug: string;
  status: string; version: number; primaryImageKey: string | null;
  minPriceMinor: number | null; maxPriceMinor: number | null;
  variantCount: number; activeVariantCount: number;
  quantityOnHand: number; quantityReserved: number;
  publishedAt: string | null; updatedAt: string;
}
```

## 9. Product moderation

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/admin/product-moderation/cases?status=OPEN&limit=20&offset=0` | - | `200 SliceResponse<ModerationCaseSummaryResponse>` |
| GET | `/admin/product-moderation/cases/{caseId}` | - | `200 ModerationCaseResponse` |
| POST | `/admin/product-moderation/products/{productId}/decision` | `ModerateProductRequest` | `200 ProductResponse` |

```ts
interface ModerateProductRequest {
  caseId?: string | null;
  action: "WARN" | "HIDE" | "REMOVE" | "RESTORE" | "DISMISS";
  reason: string;
}

interface ModerationCaseResponse {
  id: string; productId: string; productName: string; shopId: string; reporterUserId: string;
  reasonCode: string; description: string | null; evidenceKeys: string[]; status: string;
  assignedTo: string | null; resolutionAction: string | null; resolutionNote: string | null;
  resolvedAt: string | null; createdAt: string; updatedAt: string;
}

interface ModerationCaseSummaryResponse {
  id: string; productId: string; productName: string; shopId: string; reporterUserId: string;
  reasonCode: string; status: string; assignedTo: string | null; createdAt: string; updatedAt: string;
}
```

## 10. Cart

| Method | Endpoint | Request | Response |
|---|---|---|---|
| GET | `/cart` | - | `200 CartResponse` |
| POST | `/cart/items` | `AddCartItemRequest` | `201 CartResponse` |
| PUT | `/cart/items/{itemId}` | `UpdateCartItemRequest` | `200 CartResponse` |
| DELETE | `/cart/items/{itemId}?expectedVersion=N` | - | `200 CartResponse` |
| PUT | `/cart/selection` | `UpdateCartSelectionRequest` | `200 CartResponse` |

```ts
interface AddCartItemRequest {
  expectedVersion: number;
  variantId: string;
  quantity: number;             // 1..99
}

interface UpdateCartItemRequest {
  expectedVersion: number;
  quantity: number;             // 1..99
  selected: boolean;
}

interface UpdateCartSelectionRequest {
  expectedVersion: number;
  itemIds: string[];
  selected: boolean;
}

type CartAvailability = "AVAILABLE" | "OUT_OF_STOCK" | "INSUFFICIENT_STOCK" | "UNAVAILABLE";

interface CartItemResponse {
  id: string;
  variantId: string;
  productId: string;
  productName: string;
  productSlug: string;
  sellerSku: string;
  options: Record<string, string>;
  imageKey: string | null;
  unitPriceMinor: number;
  compareAtPriceMinor: number | null;
  quantity: number;
  selected: boolean;
  availableQuantity: number;
  availability: CartAvailability;
}

interface CartResponse {
  id: string;
  version: number;
  distinctItemCount: number;
  totalQuantity: number;
  selectedItemCount: number;
  selectedItemsSubtotalMinor: number;
  shops: Array<{
    shopId: string;
    shopName: string;
    shopSlug: string;
    defaultShippingFeeMinor: number;
    items: CartItemResponse[];
  }>;
  updatedAt: string;
}
```

Frontend phải lưu `CartResponse.version` sau mỗi response và dùng nó làm `expectedVersion` tiếp theo. Version cũ
trả `409 CART_VERSION_CONFLICT`; khi gặp lỗi này gọi lại `GET /cart`.

## 11. Checkout preview

| Method | Endpoint | Request | Response |
|---|---|---|---|
| POST | `/checkout/preview` | `CheckoutPreviewRequest` | `200 CheckoutPreviewResponse` |

```ts
interface CheckoutPreviewRequest {
  expectedCartVersion: number;
  addressId: string;
  expectedUnitPrices?: Record<string, number>; // key là variantId
}

type CheckoutIssueCode =
  | "PRODUCT_UNAVAILABLE"
  | "OUT_OF_STOCK"
  | "INSUFFICIENT_STOCK"
  | "PRICE_CHANGED";

interface CheckoutPreviewResponse {
  snapshotId: string | null;
  readyForOrder: boolean;
  cartVersion: number;
  currency: "VND";
  recipient: {
    recipientName: string;
    recipientPhone: string;
    addressLine1: string;
    addressLine2: string | null;
    ward: string | null;
    district: string | null;
    province: string;
    provinceCode: string | null;
    postalCode: string | null;
    countryCode: string;
  };
  shops: Array<{
    shopId: string;
    shopName: string;
    shopSlug: string;
    itemsSubtotalMinor: number;
    shippingFeeMinor: number;
    totalMinor: number;
    items: Array<{
      cartItemId: string;
      variantId: string;
      productId: string;
      productName: string;
      sellerSku: string;
      options: Record<string, string>;
      imageKey: string | null;
      unitPriceMinor: number;
      quantity: number;
      lineSubtotalMinor: number;
    }>;
  }>;
  itemsSubtotalMinor: number;
  shippingFeeMinor: number;
  discountTotalMinor: number;
  grandTotalMinor: number;
  issues: Array<{
    cartItemId: string;
    variantId: string;
    code: CheckoutIssueCode;
    message: string;
    expectedUnitPriceMinor: number | null;
    currentUnitPriceMinor: number;
    requestedQuantity: number;
    availableQuantity: number;
  }>;
  expiresAt: string | null;
}
```

Chỉ chuyển sang bước đặt hàng khi `readyForOrder === true` và `snapshotId !== null`. Nếu giá hoặc tồn kho thay đổi,
API vẫn trả `200` nhưng `readyForOrder=false`, `snapshotId=null` và có `issues`. Snapshot hợp lệ trong 15 phút.

Lỗi riêng: `404 CHECKOUT_ADDRESS_NOT_FOUND`, `409 CART_VERSION_CONFLICT`,
`409 CHECKOUT_SNAPSHOT_CONFLICT`, `422 CHECKOUT_CART_EMPTY`.

## 12. Chưa có API nghiệp vụ

Các bảng database đã tồn tại nhưng hiện chưa có controller/service hoàn chỉnh cho:

- `POST /orders` và API danh sách/chi tiết/xử lý order
- inventory reservation khi tạo order
- payment intent và payment callback
- shipment/tracking
- voucher redemption
- review
- return/refund

