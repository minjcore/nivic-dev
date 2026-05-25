#pragma once

#ifdef __APPLE__
#  include <CommonCrypto/CommonHMAC.h>
#  include <CommonCrypto/CommonDigest.h>
#  define saving_sha256(data,len,out) \
       CC_SHA256((data),(CC_LONG)(len),(out))
#else
   /* Linux — use OpenSSL */
#  include <openssl/hmac.h>
#  include <openssl/evp.h>
#  include <openssl/sha.h>
#  define CC_SHA256_DIGEST_LENGTH 32
#  define saving_sha256(data,len,out) \
       SHA256((const unsigned char*)(data),(size_t)(len),(unsigned char*)(out))
#  define kCCHmacAlgSHA256        0

static inline void CCHmac(int alg,
    const void *key,  size_t key_len,
    const void *data, size_t data_len,
    void *out)
{
    (void)alg;
    unsigned int len = CC_SHA256_DIGEST_LENGTH;
    HMAC(EVP_sha256(), key, (int)key_len, data, (int)data_len, out, &len);
}
#endif
