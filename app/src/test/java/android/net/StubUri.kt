package android.net

import android.os.Parcel
import java.net.URI

class StubUri private constructor(private val raw: String) : Uri() {
    private val jUri = URI.create(raw)

    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun getAuthority(): String? = jUri.authority
    override fun getEncodedAuthority(): String? = jUri.rawAuthority
    override fun getEncodedFragment(): String? = jUri.rawFragment
    override fun getEncodedPath(): String? = jUri.rawPath
    override fun getEncodedQuery(): String? = jUri.rawQuery
    override fun getEncodedSchemeSpecificPart(): String? = jUri.rawSchemeSpecificPart
    override fun getEncodedUserInfo(): String? = jUri.rawUserInfo
    override fun getFragment(): String? = jUri.fragment
    override fun getHost(): String? = jUri.host
    override fun getLastPathSegment(): String? = jUri.path?.trimEnd('/')?.substringAfterLast('/')
    override fun getPath(): String? = jUri.path
    override fun getPathSegments(): MutableList<String> =
        jUri.path.orEmpty().split('/').filter { it.isNotEmpty() }.toMutableList()
    override fun getPort(): Int = jUri.port
    override fun getQuery(): String? = jUri.query
    override fun getScheme(): String? = jUri.scheme
    override fun getSchemeSpecificPart(): String? = jUri.schemeSpecificPart
    override fun getUserInfo(): String? = jUri.userInfo
    override fun isHierarchical(): Boolean = !jUri.isOpaque
    override fun isRelative(): Boolean = !jUri.isAbsolute
    override fun toString(): String = raw
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {}

    companion object {
        fun of(url: String): Uri = StubUri(url)
    }
}
