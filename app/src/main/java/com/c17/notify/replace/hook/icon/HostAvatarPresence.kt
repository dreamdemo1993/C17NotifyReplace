package com.c17.notify.replace.hook.icon

import android.app.Notification
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.util.LruCache
import kotlin.math.abs

/**
 * Whether ColorOS has anything to compose its "personal avatar + app-icon badge" out of.
 *
 * The host builds that composition from the notification itself, so a picked package is only worth
 * yielding to when the notification actually carries an avatar. An ongoing / foreground-service
 * notification from the same package — WeChat's "视频通话中", a media session, a download — carries
 * none, and yielding there does not preserve a photo: it just hands the row over to the host, which
 * falls back to the bare `smallIcon` and the themed icon is lost.
 *
 * Detection reads extras rather than the `Notification` API because the module only ever sees the
 * posted notification. Note that the keys are not a reliable signal on their own: WeChat's call
 * notification ships `android.largeIcon` present but null, so the value is probed, never the key.
 *
 * The heuristic is deliberately limited to the sources ColorOS actually uses — the large icon
 * (WeChat and QQ put the sender's photo there), a `Person`, or a messaging style. Anything else
 * keeps the previous behaviour, i.e. the pick still yields.
 */
internal object HostAvatarPresence {

    /**
     * The answer to "will the host paint a contact photo here?", with the evidence it was reached
     * on, so a bug report can be diagnosed from the log alone.
     */
    internal data class Verdict(
        val renderable: Boolean,
        val reason: String,
        val difference: Double = -1.0,
    )

    fun contains(sbn: StatusBarNotification?): Boolean {
        val extras = sbn?.notification?.extras ?: return false
        return hasLargeIcon(extras) || hasPeople(extras) || isMessaging(extras)
    }

    /**
     * A collapsed-group summary never carries a contact photo: ColorOS synthesises it from its
     * children, so anything composed from it is the raw monochrome `smallIcon`. Rows like that must
     * be replaced even for a picked package, otherwise the badge shows a blank block.
     *
     * Written against the public flag because `Notification.isGroupSummary()` is a hidden API and
     * would not compile against the public SDK.
     */
    fun isGroupSummary(sbn: StatusBarNotification?): Boolean {
        val flags = sbn?.notification?.flags ?: return false
        return (flags and Notification.FLAG_GROUP_SUMMARY) != 0
    }

    /**
     * Whether the host is *allowed* to compose an avatar for this notification, which is the
     * question the yield decision actually asks.
     *
     * [contains] answers a narrower one ("an avatar bit is present in the extras right now") and
     * is too strict as a yield gate: after a reboot WeChat's first message is posted from a cold
     * process and can reach SystemUI before the sender photo is attached, so the extras probe
     * misses and the row falls back to the themed icon — the very regression users see as "first
     * message after a reboot loses the avatar".
     *
     * Message-shaped notifications (category msg / email / social) are therefore trusted to have
     * an avatar even when the probe misses, while ongoing and foreground-service notifications are
     * excluded: WeChat's "视频通话中", media sessions and downloads carry no photo at all, and
     * yielding there is exactly what turned the themed icon into a bare small icon (3.5.3).
     */
    fun mayCompose(sbn: StatusBarNotification?): Boolean {
        if (sbn == null) return true
        if (contains(sbn)) return true
        val notification = sbn.notification ?: return true
        if ((notification.flags and AVATAR_LESS_FLAGS) != 0) return false
        return notification.category?.let { it in AVATAR_CATEGORIES } == true
    }

    /**
     * The question the yield decision really asks, answered against the artwork instead of the
     * metadata: will ColorOS paint a contact photo for this row?
     *
     * [mayCompose] is too generous on a cold start. WeChat's first message after a reboot is
     * posted while the app is still booting, and it can ship an icon of its own in
     * `android.largeIcon` as a placeholder until the sender photo is loaded. `contains` sees a
     * large icon, the category says "message", so the row yields to the host — and the host then
     * composes "app icon + badge", which is neither the avatar nor the themed icon. The visible
     * symptom is exactly "the first WeChat message after a reboot is not themed, later ones are".
     *
     * So the large icon is decoded and compared against the package's launcher icon. A large icon
     * that *is* the launcher icon cannot be a contact photo, and the row is replaced like any
     * other. `Person` / messaging-style notifications keep yielding without the pixel check: on
     * those the host reads the avatar from its own contact store, not from `largeIcon`.
     */
    fun canRenderAvatar(
        context: Context?,
        sbn: StatusBarNotification?,
        allowGroupSummaryAvatar: Boolean = false,
    ): Boolean = evaluate(context, sbn, allowGroupSummaryAvatar).renderable

    /**
     * @param allowGroupSummaryAvatar also ask the artwork question for a row the app flagged as a
     * group summary. Aggregated rows (WeChat's "[2条] …") are summaries by definition, yet the
     * headline account behind them can still ship its own avatar in the large icon. The panel badge
     * hook and the lock-screen capsule pass `true` here: on those rows the host never composes a
     * photo itself, so the module has to compose it rather than yield. The metadata shortcuts are
     * skipped for summaries — only the artwork can tell a photo from the app icon there.
     */
    fun evaluate(
        context: Context?,
        sbn: StatusBarNotification?,
        allowGroupSummaryAvatar: Boolean = false,
    ): Verdict {
        if (sbn == null) return Verdict(renderable = true, reason = "no-notification")
        val summary = isGroupSummary(sbn)
        if (summary && !allowGroupSummaryAvatar) return Verdict(renderable = false, reason = REASON_GROUP_SUMMARY)
        val notification = sbn.notification ?: return Verdict(renderable = true, reason = "no-notification")
        val extras = notification.extras
        if (!summary) {
            if (hasPeople(extras)) return Verdict(renderable = true, reason = "person")
            if (isMessaging(extras)) return Verdict(renderable = true, reason = "messaging-style")
        }
        if (context == null) {
            return if (summary) {
                Verdict(renderable = false, reason = REASON_GROUP_SUMMARY)
            } else {
                Verdict(mayCompose(sbn), "no-context")
            }
        }
        // No large icon means no avatar, full stop — answered from every log session so far:
        // every row that ever showed a host-composed photo (difference 80–100 against the
        // launcher icon) carried a largeIcon, and both rows observed *without* one (the WeChat
        // group mention and the 服务号 push) had the host paint the bare app icon, never a
        // picture. Trusting the message category here (the 5.2/5.3 yield) traded a deterministic
        // theme icon for a coin flip the guard then had to repair at draw time. A photo that
        // genuinely arrives later comes with a re-post — WeChat re-posts the notification once
        // the sender picture loads — and the re-post is judged afresh with the large icon
        // present, so the row still turns into "avatar + badge" on its own.
        val largeIcon = largeIconOf(notification, extras)
            ?: return if (summary) {
                Verdict(renderable = false, reason = REASON_GROUP_SUMMARY)
            } else {
                Verdict(renderable = false, reason = REASON_NO_LARGE_ICON)
            }
        val cacheKey = sbn.key + "#" + System.identityHashCode(largeIcon)
        avatarDecisions.get(cacheKey)?.let { return it.forRowType(summary) }
        val verdict = runCatching { judge(context, sbn.packageName, largeIcon) }
            .getOrElse {
                if (summary) {
                    Verdict(renderable = false, reason = REASON_GROUP_SUMMARY)
                } else {
                    Verdict(mayCompose(sbn), "probe-failed:${it.javaClass.simpleName}")
                }
            }
        avatarDecisions.put(cacheKey, verdict)
        return verdict.forRowType(summary)
    }

    /**
     * A summary row whose artwork is *not* a photo keeps reporting `group-summary`: the caller
     * treats that reason as "replace with the themed icon", and a launcher-icon placeholder on an
     * aggregated row is exactly that. Only a real photo is allowed through.
     */
    private fun Verdict.forRowType(summary: Boolean): Verdict =
        if (summary && !renderable) copy(reason = REASON_GROUP_SUMMARY) else this

    /**
     * Whether a drawable the host already composed is, visually, just the app's own launcher icon.
     *
     * `getHeaderIconBadgeDrawable` is a pure LruCache lookup (decompiled: it only calls
     * `headerIconBadgeCache.get(key)`), so whatever it hands back was composed earlier. A cached
     * result that pixel-matches the launcher icon is a composition from the placeholder era —
     * made before the app attached the sender photo — and must be evicted so the host recomposes
     * from the notification it has *now*. The comparison is deliberately coarse — the host rounds,
     * pads and badges the picture — so only the centre of the artwork is compared.
     */
    fun looksLikeLauncherIcon(
        context: Context?,
        packageName: String?,
        drawable: Drawable?,
    ): Boolean {
        if (context == null || packageName.isNullOrEmpty() || drawable == null) return false
        val appIcon = runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull() ?: return false
        val difference = artworkDifference(drawable, appIcon) ?: return false
        return difference < HOSTED_PLACEHOLDER_TOLERANCE
    }

    /**
     * Whether two drawables are, visually, the same picture.
     *
     * The header guard uses this with the artwork it saw in the slot *before* it wrote the themed
     * icon: whatever the host paints when it repaints a row from its own cache is that artwork
     * again, so identity-by-content says "this is host placeholder art, take the slot back"
     * without having to name it. The appearance predicates ([looksLikeLauncherIcon],
     * [looksBlank], [looksLikeMonochromeMask]) cannot do that on their own — ColorOS repaints
     * `mIcon` from `Notification#smallIcon`, which for most apps is a tinted brand glyph, so it
     * matches none of them and the guard used to read it as "host content we must not cover" and
     * stop for good (report: the single row of a package falls back to the raw icon).
     */
    fun looksLikeSameArtwork(left: Drawable?, right: Drawable?): Boolean {
        if (left == null || right == null) return false
        if (left === right) return true
        val difference = artworkDifference(left, right) ?: return false
        return difference < HOSTED_PLACEHOLDER_TOLERANCE
    }

    /**
     * Whether [drawable] is the notification's own `smallIcon` — the artwork ColorOS refills the
     * header slot with when it has no avatar to compose.
     *
     * This is the refill the header guard used to misread. It is neither the launcher icon (apps
     * ship a monochrome glyph for the status bar) nor a blank sheet nor the white mask, so all
     * three appearance checks said "not a placeholder, leave it alone" and the guard stopped —
     * a plain single row then sat on the raw icon until the next bind. Colors are compared first,
     * then the alpha silhouette, because the host tints the glyph before painting it and a tinted
     * copy no longer matches pixel-for-pixel.
     */
    fun looksLikeNotificationSmallIcon(
        context: Context?,
        sbn: StatusBarNotification?,
        drawable: Drawable?,
    ): Boolean {
        if (context == null || sbn == null || drawable == null) return false
        val smallIcon = sbn.notification?.smallIcon ?: return false
        val loaded = runCatching { smallIcon.loadDrawable(context) }.getOrNull() ?: return false
        artworkDifference(drawable, loaded)?.let { if (it < HOSTED_PLACEHOLDER_TOLERANCE) return true }
        val shape = alphaDifference(drawable, loaded) ?: return false
        return shape < HOSTED_SHAPE_TOLERANCE
    }

    /**
     * The photo the host would compose from, decoded through the very same lookup [evaluate]
     * judges. Callers must use this instead of reading `getLargeIcon()` themselves: a row is
     * classified as "has a photo" through [largeIconOf], and decoding through a narrower path is
     * how a row ends up marked renderable with nothing to draw.
     */
    fun avatarDrawable(context: Context?, sbn: StatusBarNotification?): Drawable? {
        if (context == null) return null
        val notification = sbn?.notification ?: return null
        val icon = largeIconOf(notification, notification.extras) ?: return null
        return runCatching { icon.loadDrawable(context) }.getOrNull()
    }

    /**
     * Whether a drawable already on screen is a solid fill instead of a picture.
     *
     * Used by the panel guard that repairs rows the host painted blank: the check has to run on the
     * artwork the host produced, not on the notification it came from.
     */
    fun looksBlank(drawable: Drawable?): Boolean = drawable != null && isBlankArtwork(drawable)

    /**
     * Whether a drawable already on screen is the host's own monochrome notification mask — the
     * plain white squircle ColorOS paints for a collapsed group that has no avatar, with at most a
     * faint single-colour shape inside. The SMS group row is the canonical case: a white bubble on
     * a white pill. [looksBlank] only catches a perfectly flat fill, so this complements it for the
     * guard: a real photo varies far more than this across the centre, a themed icon is colourful.
     */
    fun looksLikeMonochromeMask(drawable: Drawable?): Boolean {
        val drawable = drawable ?: return false
        val pixels = sample(drawable) ?: return false
        var sum = 0.0
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var count = 0
        for (y in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
            for (x in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
                val pixel = pixels[y * SAMPLE_SIZE + x]
                if ((pixel ushr 24) < 32) continue
                val luminance = 0.299 * ((pixel ushr 16) and 0xFF) +
                    0.587 * ((pixel ushr 8) and 0xFF) +
                    0.114 * (pixel and 0xFF)
                sum += luminance
                if (luminance < min) min = luminance
                if (luminance > max) max = luminance
                count++
            }
        }
        if (count == 0) return false
        val mean = sum / count
        return mean >= MASK_WHITE_LUMINANCE && (max - min) < MASK_SPREAD_TOLERANCE
    }

    private fun judge(
        context: Context,
        packageName: String,
        largeIcon: Icon,
    ): Verdict {
        // A large icon that points at the app's own resources is the app's brand artwork, not a
        // contact photo. Banking and utility apps post their launcher art there (Bank of China's
        // green logo, for one), and the pixel comparison below cannot always tell it apart from
        // the launcher icon once the host crops and pads it. Reading the icon's own type settles
        // the question without decoding anything.
        if (isAppOwnResource(largeIcon, packageName)) {
            return Verdict(renderable = false, reason = REASON_APP_ARTWORK)
        }
        val avatar = largeIcon.loadDrawable(context)
            ?: return Verdict(renderable = false, reason = "large-icon-undecodable")
        // A blank canvas is not a photo. ColorOS' aggregated rows (the SMS auto-group, for one) ship
        // an all-white large icon, and composing "avatar + badge" out of it paints exactly what the
        // artwork contains: a white squircle. Treat it as "nothing to compose" so the row keeps the
        // themed icon instead.
        if (isBlankArtwork(avatar)) {
            return Verdict(renderable = false, reason = REASON_BLANK_ARTWORK)
        }
        val appIcon = runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull() ?: return Verdict(renderable = true, reason = "app-icon-unavailable")
        val difference = artworkDifference(avatar, appIcon)
            ?: return Verdict(renderable = true, reason = "sample-failed")
        // The same artwork re-cropped is not always a pixel match: a bank posts its logo as a
        // square picture while the launcher icon is the same logo inside a themed squircle, so the
        // raw difference lands just above the placeholder threshold. Falling back to "the mean
        // colour is the same brand colour" catches those without touching real photos, whose
        // palette has nothing to do with the app icon's.
        val sameBrandArtwork = difference < BRAND_PIXEL_TOLERANCE &&
            (meanColorDelta(avatar, appIcon) ?: Double.MAX_VALUE) < BRAND_COLOR_TOLERANCE
        val placeholder = difference < PLACEHOLDER_TOLERANCE || sameBrandArtwork
        return Verdict(
            renderable = !placeholder,
            reason = when {
                difference < PLACEHOLDER_TOLERANCE -> REASON_LAUNCHER_PLACEHOLDER
                sameBrandArtwork -> REASON_APP_ARTWORK
                else -> REASON_CONTACT_PHOTO
            },
            difference = difference,
        )
    }

    /**
     * `Icon.TYPE_RESOURCE` names a drawable inside a package. When that package is the notifying
     * app itself, the "large icon" is bundled artwork — a logo, a product shot — not a photo of a
     * person, and composing "avatar + badge" out of it just draws the app icon in a circle.
     */
    private fun isAppOwnResource(icon: Icon, packageName: String): Boolean = runCatching {
        icon.type == Icon.TYPE_RESOURCE && icon.resPackage == packageName
    }.getOrDefault(false)

    /**
     * Mean per-channel distance between the two samples, i.e. how far apart their overall colour
     * is. Two renderings of one brand mark stay within a few steps of each other; a photo next to
     * a green logo does not.
     */
    private fun meanColorDelta(left: Drawable, right: Drawable): Double? {
        val first = meanColor(left) ?: return null
        val second = meanColor(right) ?: return null
        return (
            abs(first[0] - second[0]) +
                abs(first[1] - second[1]) +
                abs(first[2] - second[2])
            ) / 3.0
    }

    private fun meanColor(drawable: Drawable): DoubleArray? {
        val pixels = sample(drawable) ?: return null
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (y in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
            for (x in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
                val pixel = pixels[y * SAMPLE_SIZE + x]
                if ((pixel ushr 24) < 32) continue
                red += (pixel ushr 16) and 0xFF
                green += (pixel ushr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }
        if (count == 0) return null
        return doubleArrayOf(
            red.toDouble() / count,
            green.toDouble() / count,
            blue.toDouble() / count,
        )
    }

    /**
     * True when the artwork carries no picture at all — a solid fill, the shape ColorOS uses as a
     * placeholder while it has no avatar to draw. Judged on the same centre window the placeholder
     * comparison uses, on luminance only: any real photo, even a monochrome one, varies far more
     * than the threshold across that area.
     */
    private fun isBlankArtwork(drawable: Drawable): Boolean {
        val pixels = sample(drawable) ?: return false
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var count = 0
        for (y in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
            for (x in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
                val pixel = pixels[y * SAMPLE_SIZE + x]
                if ((pixel ushr 24) < 32) continue
                val luminance = 0.299 * ((pixel ushr 16) and 0xFF) +
                    0.587 * ((pixel ushr 8) and 0xFF) +
                    0.114 * (pixel and 0xFF)
                if (luminance < min) min = luminance
                if (luminance > max) max = luminance
                count++
            }
        }
        if (count == 0) return true
        return (max - min) < BLANK_ARTWORK_TOLERANCE
    }

    /**
     * Mean per-channel difference over the centre of the artwork.
     *
     * Both pictures are drawn into the same 16x16 box and only the middle 10x10 is compared: that
     * drops the padding, rounding and badge the host adds around the edges while keeping the part
     * that actually identifies the image. Transparent samples are skipped so a circular avatar is
     * not penalised for its corners.
     */
    private fun artworkDifference(left: Drawable, right: Drawable): Double? {
        val leftSample = sample(left) ?: return null
        val rightSample = sample(right) ?: return null
        var total = 0L
        var count = 0
        for (y in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
            for (x in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
                val index = y * SAMPLE_SIZE + x
                val first = leftSample[index]
                val second = rightSample[index]
                if ((first ushr 24) < 32 || (second ushr 24) < 32) continue
                total += abs(((first ushr 16) and 0xFF) - ((second ushr 16) and 0xFF)).toLong()
                total += abs(((first ushr 8) and 0xFF) - ((second ushr 8) and 0xFF)).toLong()
                total += abs((first and 0xFF) - (second and 0xFF)).toLong()
                count++
            }
        }
        if (count == 0) return null
        return total.toDouble() / (count * 3.0)
    }

    /**
     * Mean absolute alpha difference over the centre of two silhouettes, 0–255.
     *
     * The tint-proof half of [artworkDifference]: ColorOS paints the themed/raw glyph through a
     * color filter, so the RGB values can be arbitrarily far apart while the shape — the only part
     * that says *which* picture this is — is identical.
     */
    private fun alphaDifference(left: Drawable, right: Drawable): Double? {
        val leftSample = sample(left) ?: return null
        val rightSample = sample(right) ?: return null
        var total = 0L
        var count = 0
        for (y in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
            for (x in SAMPLE_MARGIN until SAMPLE_SIZE - SAMPLE_MARGIN) {
                val index = y * SAMPLE_SIZE + x
                val first = (leftSample[index] ushr 24) and 0xFF
                val second = (rightSample[index] ushr 24) and 0xFF
                total += abs(first - second).toLong()
                count++
            }
        }
        if (count == 0) return null
        return total.toDouble() / count
    }

    private fun sample(drawable: Drawable): IntArray? = try {
        val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(drawable.bounds)
        drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        drawable.draw(Canvas(bitmap))
        drawable.bounds = previousBounds
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        bitmap.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        bitmap.recycle()
        pixels
    } catch (_: Throwable) {
        null
    }

    /**
     * `Notification#getLargeIcon()` is the modern accessor, but apps that still post the legacy
     * `android.largeIcon` bitmap leave it null on some builds — hence the extras fallback. Both
     * forms are accepted there: the platform stores a `Bitmap` for the legacy path and an `Icon`
     * when the app went through `IconCompat`, and missing either one is what made a row report
     * "no-large-icon" while carrying a perfectly good photo.
     */
    private fun largeIconOf(notification: Notification, extras: Bundle): Icon? =
        runCatching { notification.getLargeIcon() }.getOrNull()
            ?: parcelableIcon(extras, Notification.EXTRA_LARGE_ICON)
            ?: parcelableIcon(extras, Notification.EXTRA_LARGE_ICON_BIG)

    private fun parcelableIcon(extras: Bundle, key: String): Icon? = runCatching {
        when (val value = parcelable(extras, key)) {
            is Bitmap -> Icon.createWithBitmap(value)
            is Icon -> value
            else -> null
        }
    }.getOrNull()

    private fun hasLargeIcon(extras: Bundle): Boolean =
        parcelable(extras, Notification.EXTRA_LARGE_ICON) != null ||
            parcelable(extras, Notification.EXTRA_LARGE_ICON_BIG) != null

    @Suppress("DEPRECATION") // BundleCompat only widens the API 33 signature for the same call.
    private fun hasPeople(extras: Bundle): Boolean =
        !extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST).isNullOrEmpty()

    private fun isMessaging(extras: Bundle): Boolean =
        extras.containsKey(Notification.EXTRA_MESSAGES) ||
            extras.containsKey(Notification.EXTRA_HISTORIC_MESSAGES) ||
            extras.containsKey(Notification.EXTRA_CONVERSATION_TITLE) ||
            extras.getCharSequence(Notification.EXTRA_TEMPLATE)?.toString() == MESSAGING_STYLE

    @Suppress("DEPRECATION") // Same reasoning as above: the key-based read is the portable form.
    private fun parcelable(extras: Bundle, key: String): Parcelable? =
        extras.getParcelable(key)

    private const val MESSAGING_STYLE = "android.app.Notification\$MessagingStyle"

    /** Ongoing / foreground-service rows never carry a contact photo to compose. */
    private const val AVATAR_LESS_FLAGS =
        Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE

    /** Side of the square both icons are scaled to before being compared. */
    private const val SAMPLE_SIZE = 16

    /** Border pixels dropped from the comparison, which is where host rounding and badges live. */
    private const val SAMPLE_MARGIN = 3

    /** Mean per-channel difference below which the avatar slot holds the launcher icon. */
    private const val PLACEHOLDER_TOLERANCE = 22.0

    /** Same comparison, but against a drawable the host already composed (padded, rounded). */
    private const val HOSTED_PLACEHOLDER_TOLERANCE = 34.0

    /**
     * Upper bound of the pixel difference still accepted as "the same artwork, re-cropped". Above
     * it the large icon holds a different picture and the row keeps yielding to the host.
     */
    private const val BRAND_PIXEL_TOLERANCE = 48.0

    /**
     * Mean per-channel distance below which two drawings share a palette. Two renderings of one
     * brand mark stay well under this; a photo next to a coloured logo never does.
     */
    private const val BRAND_COLOR_TOLERANCE = 24.0

    /**
     * Luminance spread below which the avatar slot is a solid fill instead of a picture. One channel
     * step or two of JPEG/rounding noise stays well under it; no photo does.
     */
    private const val BLANK_ARTWORK_TOLERANCE = 6.0

    /** Mean luminance above which a mostly-white mask counts as "no artwork". */
    private const val MASK_WHITE_LUMINANCE = 224.0

    /** Mean luminance spread below which a mostly-white drawable is the host's monochrome mask, not a photo. */
    private const val MASK_SPREAD_TOLERANCE = 48.0

    /**
     * Mean alpha difference (0–255) below which two drawables are the same silhouette. A tinted
     * repaint of the small icon stays far under this; a photo next to a glyph never does.
     */
    private const val HOSTED_SHAPE_TOLERANCE = 48.0

    /** Keyed by notification key + large-icon identity, so a re-post is re-evaluated. */
    private val avatarDecisions = LruCache<String, Verdict>(64)

    /** [Verdict.reason] meaning the row is a collapsed group summary with no photo of its own. */
    const val REASON_GROUP_SUMMARY = "group-summary"

    /** [Verdict.reason] meaning the avatar slot really holds a sender photo. */
    const val REASON_CONTACT_PHOTO = "contact-photo"

    /** [Verdict.reason] meaning the avatar slot holds the app's own icon. */
    const val REASON_LAUNCHER_PLACEHOLDER = "launcher-icon-placeholder"

    /** [Verdict.reason] meaning the avatar slot holds a solid fill with no picture in it. */
    const val REASON_BLANK_ARTWORK = "blank-artwork"

    /**
     * [Verdict.reason] meaning the artwork is the app's own brand picture (a bundled logo), so
     * there is no person in it and the row keeps the themed icon.
     */
    const val REASON_APP_ARTWORK = "app-artwork"

    /**
     * [Verdict.reason] meaning the notification carries no large icon at all, so the host has
     * nothing to build an avatar out of and would paint the bare app icon into the avatar slot.
     */
    const val REASON_NO_LARGE_ICON = "no-large-icon"

    /** Notification categories ColorOS renders with a per-sender avatar. */
    private val AVATAR_CATEGORIES = setOf(
        Notification.CATEGORY_MESSAGE,
        Notification.CATEGORY_EMAIL,
        Notification.CATEGORY_SOCIAL,
    )
}
