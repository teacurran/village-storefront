import { getProducts } from '../../../lib/storefront-api'
import { notFound } from 'next/navigation'

export default async function ProductPage({
  params,
}: {
  params: { slug: string }
}) {
  let product = null
  let error = null

  try {
    // Note: In production, you'd want a dedicated getProductBySlug API
    // For now, we search by slug and take the first result
    const result = await getProducts(params.slug, 1, 1)
    product = result.products.find(p => p.slug === params.slug)

    if (!product) {
      notFound()
    }
  } catch (err) {
    error = err instanceof Error ? err.message : 'Failed to load product'
  }

  if (error) {
    return (
      <div className="p-4 bg-red-50 border border-red-200 rounded-md">
        <p className="text-red-800">{error}</p>
      </div>
    )
  }

  if (!product) {
    notFound()
  }

  return (
    <div className="bg-white rounded-lg shadow-sm">
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 p-8">
        {/* Product Image */}
        <div className="aspect-square bg-gray-100 rounded-lg flex items-center justify-center">
          {product.imageUrl ? (
            <img
              src={product.imageUrl}
              alt={product.name}
              className="w-full h-full object-cover rounded-lg"
            />
          ) : (
            <svg
              className="w-32 h-32 text-gray-300"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"
              />
            </svg>
          )}
        </div>

        {/* Product Details */}
        <div>
          <h1 className="text-3xl font-bold text-gray-900 mb-4">
            {product.name}
          </h1>

          <p className="text-4xl font-bold text-blue-600 mb-6">
            ${product.price}
          </p>

          {product.description && (
            <div className="mb-6">
              <h2 className="text-lg font-semibold text-gray-900 mb-2">
                Description
              </h2>
              <p className="text-gray-600 leading-relaxed">
                {product.description}
              </p>
            </div>
          )}

          {/* Variants */}
          {product.variants && product.variants.length > 0 && (
            <div className="mb-6">
              <h2 className="text-lg font-semibold text-gray-900 mb-3">
                Variants
              </h2>
              <div className="space-y-2">
                {product.variants.map((variant) => (
                  <div
                    key={variant.id}
                    className="flex items-center justify-between p-3 border border-gray-200 rounded-md hover:border-blue-500 cursor-pointer"
                  >
                    <div>
                      <p className="font-medium text-gray-900">{variant.name}</p>
                      <p className="text-sm text-gray-500">
                        {variant.inventoryQuantity > 0
                          ? `${variant.inventoryQuantity} in stock`
                          : 'Out of stock'}
                      </p>
                    </div>
                    <p className="text-lg font-bold text-blue-600">
                      ${variant.price}
                    </p>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Add to Cart (placeholder - requires session management) */}
          <div className="border-t pt-6">
            <p className="text-sm text-gray-500 mb-4">
              <strong>Note:</strong> Add to cart functionality requires session management.
              See the API client implementation in <code className="bg-gray-100 px-1 py-0.5 rounded">lib/storefront-api.ts</code>
            </p>
            <button
              disabled
              className="w-full px-6 py-3 bg-gray-300 text-gray-500 rounded-md cursor-not-allowed"
            >
              Add to Cart (Demo Only)
            </button>
          </div>

          {/* Product Metadata */}
          <div className="mt-6 pt-6 border-t">
            <dl className="space-y-2 text-sm">
              <div className="flex justify-between">
                <dt className="text-gray-500">SKU:</dt>
                <dd className="text-gray-900 font-mono">{product.sku}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Status:</dt>
                <dd className="text-gray-900 capitalize">{product.status}</dd>
              </div>
            </dl>
          </div>
        </div>
      </div>
    </div>
  )
}
