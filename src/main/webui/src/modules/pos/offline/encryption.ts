/**
 * Encryption utilities for POS offline queue.
 *
 * Uses Web Crypto API for AES-GCM encryption of sensitive offline transaction data.
 * Encryption keys are stored in IndexedDB and never leave the client.
 *
 * References:
 * - Architecture: §3.19.10 POS offline encryption requirements
 * - Task I4.T7: Frontend encryption utility
 */

const ALGORITHM = 'AES-GCM'
const KEY_LENGTH = 256 // bits
const IV_LENGTH = 12 // bytes (recommended for GCM)

export interface EncryptedPayload {
  encryptedData: string // Base64-encoded
  iv: string // Base64-encoded initialization vector
  keyVersion: number
}

/**
 * Generate a new AES-256-GCM encryption key.
 */
export async function generateEncryptionKey(): Promise<CryptoKey> {
  return await crypto.subtle.generateKey(
    {
      name: ALGORITHM,
      length: KEY_LENGTH,
    },
    true, // extractable
    ['encrypt', 'decrypt']
  )
}

/**
 * Export encryption key to Base64 string for transmission to server during pairing.
 */
export async function exportKeyToBase64(key: CryptoKey): Promise<string> {
  const rawKey = await crypto.subtle.exportKey('raw', key)
  return arrayBufferToBase64(rawKey)
}

/**
 * Import encryption key from Base64 string (received from server during pairing).
 */
export async function importKeyFromBase64(base64Key: string): Promise<CryptoKey> {
  const rawKey = base64ToArrayBuffer(base64Key)
  return await crypto.subtle.importKey(
    'raw',
    rawKey,
    {
      name: ALGORITHM,
      length: KEY_LENGTH,
    },
    true,
    ['encrypt', 'decrypt']
  )
}

/**
 * Encrypt data using AES-GCM.
 *
 * @param data - Plain text data to encrypt (will be JSON-stringified)
 * @param key - Encryption key
 * @param keyVersion - Key version number for rotation support
 * @returns Encrypted payload with IV
 */
export async function encryptData(
  data: unknown,
  key: CryptoKey,
  keyVersion: number
): Promise<EncryptedPayload> {
  // Generate random IV
  const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH))

  // Convert data to JSON bytes
  const jsonString = JSON.stringify(data)
  const plaintext = new TextEncoder().encode(jsonString)

  // Encrypt
  const ciphertext = await crypto.subtle.encrypt(
    {
      name: ALGORITHM,
      iv: iv,
    },
    key,
    plaintext
  )

  return {
    encryptedData: arrayBufferToBase64(ciphertext),
    iv: arrayBufferToBase64(iv),
    keyVersion,
  }
}

/**
 * Decrypt data using AES-GCM.
 *
 * @param payload - Encrypted payload
 * @param key - Decryption key
 * @returns Decrypted and parsed data
 */
export async function decryptData<T = unknown>(
  payload: EncryptedPayload,
  key: CryptoKey
): Promise<T> {
  const ciphertext = base64ToArrayBuffer(payload.encryptedData)
  const iv = base64ToArrayBuffer(payload.iv)

  const plaintext = await crypto.subtle.decrypt(
    {
      name: ALGORITHM,
      iv: iv,
    },
    key,
    ciphertext
  )

  const jsonString = new TextDecoder().decode(plaintext)
  return JSON.parse(jsonString) as T
}

/**
 * Hash encryption key for server-side verification (SHA-256).
 */
export async function hashKey(key: CryptoKey): Promise<string> {
  const rawKey = await crypto.subtle.exportKey('raw', key)
  const hashBuffer = await crypto.subtle.digest('SHA-256', rawKey)
  return arrayBufferToHex(hashBuffer)
}

// Helper functions

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
}

function base64ToArrayBuffer(base64: string): ArrayBuffer {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes.buffer
}

function arrayBufferToHex(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}
