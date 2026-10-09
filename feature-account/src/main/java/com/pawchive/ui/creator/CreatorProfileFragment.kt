package com.pawchive.ui.creator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.imageview.ShapeableImageView
import com.pawchive.common.R
import com.pawchive.core.model.CreatorTag
import com.pawchive.core.model.FanCard
import com.pawchive.core.model.SimilarCreator
import com.pawchive.core.util.CreatorSiteLinks
import com.pawchive.core.util.CrashHandler
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BlockedCreatorManager
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.data.repository.CreatorSubscriptionRepository
import com.pawchive.common.databinding.FragmentCreatorProfileBinding
import com.pawchive.common.nav.AppNavigator
import com.pawchive.core.store.SettingsManager
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.core.error.ErrorMessageHelper
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.pawchive.ui.widget.SkeletonHelper

@AndroidEntryPoint
class CreatorProfileFragment : Fragment() {

    private var _binding: FragmentCreatorProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CreatorProfileViewModel by viewModels()

    private lateinit var postAdapter: PostAdapter
    @Inject
    lateinit var bookmarkManager: BookmarkManager
    @Inject
    lateinit var blockedCreatorManager: BlockedCreatorManager
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var subscriptionRepository: CreatorSubscriptionRepository
    @Inject lateinit var settingsManager: SettingsManager

    private var service: String = ""
    private var creatorId: String = ""

    private enum class PostSortOption(@param:StringRes val displayNameRes: Int) {
        NEWEST_PUBLISHED(R.string.sort_newest_published),
        OLDEST_PUBLISHED(R.string.sort_oldest_published),
        NEWEST_EDITED(R.string.sort_newest_edited),
        OLDEST_EDITED(R.string.sort_oldest_edited)
    }

    private var currentSort = PostSortOption.NEWEST_PUBLISHED
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            service = it.getString(ARG_SERVICE, "")
            creatorId = it.getString(ARG_CREATOR_ID, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreatorProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 各步骤独立容错（BUG-CREATOR-SETUP-NPE）：单步失败只记日志/提示，
        // 不再阻断后续步骤与数据加载；失败同时写入崩溃日志文件（可反混淆定位）
        runSetupStep("btnBack") {
            binding.btnBack.setOnClickListener {
                hideKeyboard()
                parentFragmentManager.popBackStack()
            }
        }
        runSetupStep("sectionPages") { setupSectionPages() }
        runSetupStep("recyclerView") { setupRecyclerView() }
        runSetupStep("bookmarkButton") { setupBookmarkButton() }
        runSetupStep("moreMenu") { setupMoreMenu() }
        runSetupStep("sortButton") { setupSortButton() }
        runSetupStep("searchView") { setupSearchView() }
        runSetupStep("observeState") { observeCreatorState() }
        runSetupStep("loadCreator") { viewModel.loadCreator(service, creatorId) }
    }

    /** 单步初始化容错（BUG-CREATOR-SETUP-NPE）。 */
    private fun runSetupStep(step: String, block: () -> Unit) {
        runCatching { block() }.onFailure { err ->
            android.util.Log.e("CreatorProfileFragment", "setup step failed: $step", err)
            CrashHandler.logNonFatal(requireContext(), "creator-setup:$step", err)
            Toast.makeText(
                context,
                "creator.$step: ${err.javaClass.simpleName} - ${err.message?.take(120)}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun observeCreatorState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    runCatching { renderCreatorState(state) }
                        .onFailure { err ->
                            android.util.Log.e(
                                "CreatorProfileFragment",
                                "render crashed service=$service creator=$creatorId",
                                err
                            )
                            Toast.makeText(
                                context,
                                "render: ${err.javaClass.simpleName} - ${err.message?.take(120)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }
    }

    private fun renderCreatorState(state: CreatorProfileUiState) {
        // 分页视图未就绪时（初始化单步失败）跳过渲染，避免二次异常（BUG-CREATOR-SETUP-NPE）
        if (!::rvCreatorPosts.isInitialized || !::layoutAnnouncements.isInitialized) return
        if (state.isLoading && state.posts.isEmpty()) {
            SkeletonHelper.show(binding.skeletonView.root, rvCreatorPosts)
        } else if (binding.skeletonView.root.visibility == View.VISIBLE) {
            SkeletonHelper.hide(binding.skeletonView.root, rvCreatorPosts)
        }

        if (state.errorMessage != null && state.posts.isEmpty()) {
            Toast.makeText(
                context,
                ErrorMessageHelper.getFriendlyMessage(context, state.errorMessage),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.tvCreatorTitle.text = state.name.ifEmpty { creatorId }
        binding.tvCreatorService.text = service.uppercase()
        setServiceLabelColor(service)
        if (state.name.isNotEmpty()) {
            CreatorNameCache.cacheCreatorName(service, creatorId, state.name)
        }
        loadAvatar()

        layoutAnnouncements.removeAllViews()
        if (state.announcements.isNotEmpty()) {
            for (announcement in state.announcements) {
                val textView = TextView(requireContext()).apply {
                    text = Html.fromHtml(announcement.content ?: "", Html.FROM_HTML_MODE_COMPACT)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    textSize = 13f
                    // 公告内容可自由选中复制（FEAT-CREATOR-ANNOUNCE-COPY）
                    setTextIsSelectable(true)
                    background = requireContext().getDrawable(R.drawable.comment_bg)
                    setPadding(12, 12, 12, 12)
                }
                layoutAnnouncements.addView(textView)
            }
        }
        tvAnnouncementsEmpty.visibility =
            if (state.announcements.isEmpty()) View.VISIBLE else View.GONE

        layoutLinks.removeAllViews()
        if (state.links.isNotEmpty()) {
            for (link in state.links) {
                val textView = TextView(requireContext()).apply {
                    text = "${link.name} (${link.service})"
                    setTextColor(resources.getColor(R.color.primary_light, null))
                    textSize = 14f
                    setPadding(0, 8, 0, 8)
                    setOnClickListener {
                        runCatching { (activity as? AppNavigator)?.openCreatorProfile(link.service, link.id) }
                            .onFailure { err ->
                                Toast.makeText(
                                    context,
                                    "open: ${err.javaClass.simpleName} - ${err.message?.take(100)}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    }
                }
                layoutLinks.addView(textView)
            }
        }
        tvLinksEmpty.visibility = if (state.links.isEmpty()) View.VISIBLE else View.GONE

        // 收藏图标合并云端状态（BUG-IMPORT-STATE）：云端收藏的作者即使本地无记录也显示已收藏
        updateBookmarkIcon(
            bookmarkManager.isCreatorBookmarked(service, creatorId) || state.cloudFavorited
        )

        renderSimilarCreators(state.similarCreators)
        renderCreatorTags(state.tags, state.selectedTag)
        renderFancards(state.fancards)

        applySort(state.posts)
        updateLoadMoreButton(state.hasMore)
    }

    /** 相似作者列表（FEAT-SIMILAR-CREATORS；FEAT-UI-190 起为独立分页的纵向列表）。 */
    private fun renderSimilarCreators(creators: List<SimilarCreator>) {
        layoutSimilar.removeAllViews()
        tvSimilarEmpty.visibility = if (creators.isEmpty()) View.VISIBLE else View.GONE

        val context = requireContext()
        val density = resources.displayMetrics.density
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        for (creator in creators) {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = (10 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                setBackgroundResource(ripple)
                setOnClickListener {
                    (activity as? AppNavigator)?.openCreatorProfile(creator.service, creator.id)
                }
            }

            val avatar = ShapeableImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(), (40 * density).toInt()
                ).apply { marginEnd = (12 * density).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(12 * density)
                    .build()
                load("https://pawchive.pw/icons/${creator.service}/${creator.id}") {
                    crossfade(true)
                    placeholder(R.drawable.ic_image)
                    error(R.drawable.ic_image_off)
                }
            }
            item.addView(avatar)

            val name = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { weight = 1f }
                text = creator.name
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(context.getColor(R.color.text_secondary))
            }
            item.addView(name)

            layoutSimilar.addView(item)
        }
    }

    // renderCreatorTags/renderFancards 的重建去重：避免每次 state 发射都重建子视图
    // 页头操作状态（FEAT-UI-190）：菜单每次打开时按此重建
    private var subscribedState = false
    private var blockedState = false

    // 分页视图（FEAT-UI-190）：帖子/公告/关联账号/相似作者迁入 ViewPager2 分页
    private lateinit var postsPageView: View
    private lateinit var announcementsPageView: View
    private lateinit var linksPageView: View
    private lateinit var similarPageView: View
    private lateinit var rvCreatorPosts: RecyclerView
    private lateinit var btnSort: TextView
    private lateinit var searchCardCreator: View
    private lateinit var searchViewCreator: androidx.appcompat.widget.SearchView
    private lateinit var layoutAnnouncements: LinearLayout
    private lateinit var layoutLinks: LinearLayout
    private lateinit var layoutSimilar: LinearLayout
    private lateinit var tvAnnouncementsEmpty: TextView
    private lateinit var tvLinksEmpty: TextView
    private lateinit var tvSimilarEmpty: TextView

    private var lastRenderedTagsKey: Pair<Int, String?>? = null
    private var lastRenderedFancardsHash: Int? = null

    /** 标签筛选区（FEAT-TAG-BROWSE）；为空时整段隐藏。选中态由 UI 状态驱动。 */
    private fun renderCreatorTags(tags: List<CreatorTag>, selectedTag: String?) {
        val key = tags.hashCode() to selectedTag
        if (key == lastRenderedTagsKey) return
        lastRenderedTagsKey = key

        binding.chipGroupTags.removeAllViews()
        if (tags.isEmpty()) {
            binding.tvTagsHeader.visibility = View.GONE
            binding.scrollTags.visibility = View.GONE
            return
        }
        binding.tvTagsHeader.visibility = View.VISIBLE
        binding.scrollTags.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(requireContext())
        // "全部" chip：selectedTag == null 时选中，点击恢复未筛选列表
        binding.chipGroupTags.addView(
            createTagChip(inflater, getString(R.string.creator_tag_all), null, selectedTag)
        )
        for (t in tags) {
            binding.chipGroupTags.addView(
                createTagChip(inflater, "${t.tag} (${t.postCount})", t.tag, selectedTag)
            )
        }
    }

    private fun createTagChip(
        inflater: LayoutInflater,
        label: String,
        tagValue: String?,
        selectedTag: String?
    ): Chip {
        val chip = inflater.inflate(R.layout.item_creator_tag, binding.chipGroupTags, false) as Chip
        chip.text = label
        chip.isChecked = selectedTag == tagValue
        chip.setOnCheckedChangeListener { _, isChecked ->
            when {
                // 选中某个标签 → 按标签筛选（tagValue = null 即"全部"）
                isChecked -> viewModel.selectTag(tagValue)
                // 再次点击已选中的具体标签 → 取消筛选；"全部" chip 的取消选中忽略
                tagValue != null -> viewModel.selectTag(null)
            }
        }
        return chip
    }

    /** 粉丝卡横向图片区（FEAT-FANCARDS）；为空时整段隐藏。 */
    private fun renderFancards(fancards: List<FanCard>) {
        if (fancards.hashCode() == lastRenderedFancardsHash) return
        lastRenderedFancardsHash = fancards.hashCode()

        binding.layoutFancards.removeAllViews()
        if (fancards.isEmpty()) {
            binding.tvFancardsHeader.visibility = View.GONE
            binding.scrollFancards.visibility = View.GONE
            return
        }
        binding.tvFancardsHeader.visibility = View.VISIBLE
        binding.scrollFancards.visibility = View.VISIBLE

        val context = requireContext()
        val density = resources.displayMetrics.density
        for (card in fancards) {
            val image = ShapeableImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (160 * density).toInt(), (90 * density).toInt()
                ).apply { marginEnd = (8 * density).toInt() }
                scaleType = ImageView.ScaleType.CENTER_CROP
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(12 * density)
                    .build()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            loadFancardImage(image, card, attempt = 0)
            binding.layoutFancards.addView(image)
        }
    }

    /**
     * 粉丝卡图片加载（FEAT-FANCARDS）：FanCard 仅含 hash + ext，
     * 按 kemono 风格路径推导 URL：/{hash[0..1]}/{hash[2..3]}/{hash}{ext}，
     * 沿用 PostAdapter 同款三级候选顺序（原图优先，缩略图兜底）。
     */
    private fun loadFancardImage(view: ShapeableImageView, card: FanCard, attempt: Int) {
        val h = card.hash
        val ext = if (card.ext.startsWith(".")) card.ext else ".${card.ext}"
        val path = if (h.length >= 4) "/${h.take(2)}/${h.substring(2, 4)}/$h$ext" else "/$h$ext"
        val urls = listOf(
            "https://img.pawchive.pw/data$path",
            "https://file.pawchive.pw/data$path",
            "https://img.pawchive.pw/thumbnail/data$path"
        )
        val isLast = attempt == urls.lastIndex
        view.load(urls[attempt]) {
            crossfade(if (settingsManager.isReduceAnimationsEnabled()) 0 else 150)
            placeholder(R.drawable.ic_image)
            if (isLast) error(R.drawable.ic_image_off)
            listener(
                onError = { _, _ ->
                    if (!isLast) loadFancardImage(view, card, attempt + 1)
                }
            )
        }
    }

    /**
     * 分页化（FEAT-UI-190）：帖子 / 公告 / 关联账号 / 相似作者 四页，
     * TabLayout + ViewPager2 左右滑动切换。四个页面视图一次性创建，
     * 数据到达后由 render 系列函数分别填充。
     */
    private fun setupSectionPages() {
        val inflater = layoutInflater
        postsPageView = inflater.inflate(R.layout.page_creator_posts, binding.vpCreatorSections, false)
        announcementsPageView = inflater.inflate(R.layout.page_creator_announcements, binding.vpCreatorSections, false)
        linksPageView = inflater.inflate(R.layout.page_creator_links, binding.vpCreatorSections, false)
        similarPageView = inflater.inflate(R.layout.page_creator_similar, binding.vpCreatorSections, false)

        rvCreatorPosts = postsPageView.findViewById(R.id.rv_creator_posts)
        // 排序/页内搜索位于主布局的 posts_controls（仅随帖子页显隐），不在分页视图内
        // （BUG-CREATOR-SETUP-NPE：曾误从分页视图 findViewById 返回 null，赋值即抛异常）
        btnSort = binding.btnSort
        searchCardCreator = binding.searchCardCreator
        searchViewCreator = binding.searchViewCreator
        layoutAnnouncements = announcementsPageView.findViewById(R.id.layout_announcements)
        tvAnnouncementsEmpty = announcementsPageView.findViewById(R.id.tv_announcements_empty)
        layoutLinks = linksPageView.findViewById(R.id.layout_links)
        tvLinksEmpty = linksPageView.findViewById(R.id.tv_links_empty)
        layoutSimilar = similarPageView.findViewById(R.id.layout_similar)
        tvSimilarEmpty = similarPageView.findViewById(R.id.tv_similar_empty)

        binding.vpCreatorSections.adapter = SectionsPagerAdapter()
        binding.vpCreatorSections.offscreenPageLimit = 1
        com.google.android.material.tabs.TabLayoutMediator(
            binding.tabLayoutCreator, binding.vpCreatorSections
        ) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.posts
                    1 -> R.string.announcements
                    2 -> R.string.linked_accounts
                    else -> R.string.similar_artists
                }
            )
        }.attach()

        binding.vpCreatorSections.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    // 排序/页内搜索仅属于帖子页
                    binding.postsControls.visibility =
                        if (position == 0) View.VISIBLE else View.GONE
                }
            }
        )
    }

    /** 四个固定分页：每个位置返回预建页面视图（无数据绑定开销）。 */
    private inner class SectionsPagerAdapter : RecyclerView.Adapter<SectionViewHolder>() {
        override fun getItemCount(): Int = 4

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
            val page = when (viewType) {
                0 -> postsPageView
                1 -> announcementsPageView
                2 -> linksPageView
                else -> similarPageView
            }
            // 页面视图从占位 parent 摘除后交由 ViewPager2 接管
            (page.parent as? ViewGroup)?.removeView(page)
            return SectionViewHolder(page)
        }

        override fun onBindViewHolder(holder: SectionViewHolder, position: Int) = Unit
    }

    private inner class SectionViewHolder(val page: View) : RecyclerView.ViewHolder(page)

    private fun setupRecyclerView() {
        postAdapter = PostAdapter(
            posts = emptyList(),
            bookmarkManager = bookmarkManager,
            authRepository = authRepository,
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            onPostClicked = { post ->
                (activity as? AppNavigator)?.openPostDetail(post.service, post.user, post.id)
            },
            onCreatorClicked = { _, _ -> },
            onBookmarkChanged = { _, _ -> },
            // FEAT-UI-192：加载更多移入列表页脚（仅在有未加载内容时显示）
            onLoadMore = { viewModel.loadMorePosts() },
            showCreatorInfo = false
        )
        rvCreatorPosts.layoutManager = LinearLayoutManager(requireContext())
        rvCreatorPosts.adapter = postAdapter
    }

    private fun setupSortButton() {
        btnSort.text = getString(currentSort.displayNameRes)
        btnSort.setOnClickListener {
            hideKeyboard()
            showSortDialog()
        }
    }

    private fun setupSearchView() {
        val focusAndShow = {
            searchViewCreator.apply {
                isIconified = false
                requestFocus()
                requestFocusFromTouch()
            }
        }
        searchViewCreator.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                hideKeyboard()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty().trim()
                applySort(viewModel.uiState.value.posts)
                return true
            }
        })
        searchCardCreator.setOnClickListener { focusAndShow() }
        searchViewCreator.setOnClickListener { focusAndShow() }
        searchViewCreator.setOnTouchListener { view, event ->
            focusAndShow()
            false
        }
    }

    private fun showSortDialog() {
        val options = PostSortOption.values().map { getString(it.displayNameRes) }.toTypedArray()
        val currentIndex = PostSortOption.values().indexOf(currentSort)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_sort)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                currentSort = PostSortOption.values()[which]
                btnSort.text = getString(currentSort.displayNameRes)
                applySort(viewModel.uiState.value.posts)
                dialog.dismiss()
            }
            .show()
    }

    private fun applySort(posts: List<com.pawchive.core.model.Post>) {
        if (posts.isEmpty()) return
        val filtered = if (searchQuery.isNotEmpty()) {
            posts.filter {
                it.title?.contains(searchQuery, ignoreCase = true) == true ||
                it.content?.contains(searchQuery, ignoreCase = true) == true
            }
        } else {
            posts
        }
        val sorted = when (currentSort) {
            PostSortOption.NEWEST_PUBLISHED -> filtered.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> filtered.sortedBy { it.published }
            PostSortOption.NEWEST_EDITED -> filtered.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> filtered.sortedBy { it.edited ?: it.published }
        }
        postAdapter.updatePosts(sorted)
    }

    private fun setupBookmarkButton() {
        // 初次展示合并云端收藏状态（BUG-IMPORT-STATE）
        val isBookmarked = bookmarkManager.isCreatorBookmarked(service, creatorId) ||
            viewModel.uiState.value.cloudFavorited
        updateBookmarkIcon(isBookmarked)

        binding.btnCreatorBookmark.setOnClickListener {
            hideKeyboard()
            val cloudFavorited = viewModel.uiState.value.cloudFavorited
            val newStatus = !(bookmarkManager.isCreatorBookmarked(service, creatorId) || cloudFavorited)
            if (newStatus) {
                bookmarkManager.bookmarkCreator(service, creatorId)
                if (authRepository.isLoggedIn()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = authRepository.addCreatorToFavorites(service, creatorId)
                        if (result.isFailure) {
                            bookmarkManager.unbookmarkCreator(service, creatorId)
                            updateBookmarkIcon(false)
                            Toast.makeText(
                                context,
                                getString(R.string.connection_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            autoSubscribeIfEnabled()
                        }
                    }
                } else {
                    autoSubscribeIfEnabled()
                }
            } else {
                bookmarkManager.unbookmarkCreator(service, creatorId)
                if (authRepository.isLoggedIn()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = authRepository.removeCreatorFromFavorites(service, creatorId)
                        if (result.isFailure) {
                            bookmarkManager.bookmarkCreator(service, creatorId)
                            updateBookmarkIcon(true)
                            Toast.makeText(
                                context,
                                getString(R.string.connection_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            // 云端移除成功后同步清掉合并状态，避免后续重渲染把图标又翻回已收藏
                            viewModel.onCreatorUnfavorited()
                        }
                    }
                }
            }
            updateBookmarkIcon(newStatus)
            Toast.makeText(
                context,
                if (newStatus) getString(R.string.bookmark_added) else getString(R.string.bookmark_removed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun autoSubscribeIfEnabled() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                if (!settingsManager.isAutoSubscribeOnBookmarkEnabled()) return@launch
                if (subscriptionRepository.isSubscribed(service, creatorId)) return@launch
                val newestPostId = viewModel.uiState.value.posts.firstOrNull()?.id
                subscriptionRepository.subscribe(
                    service = service,
                    creatorId = creatorId,
                    name = viewModel.uiState.value.name.ifEmpty { creatorId },
                    lastPostId = newestPostId
                )
                updateSubscribeIcon(true)
                Toast.makeText(context, R.string.auto_subscribed, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                android.util.Log.e("CreatorProfileFragment", "autoSubscribe failed", err)
                Toast.makeText(
                    context,
                    "autoSubscribe: ${err.javaClass.simpleName} - ${err.message?.take(100)}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateBookmarkIcon(isBookmarked: Boolean) {
        binding.btnCreatorBookmark.setImageResource(
            if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
        )
    }

    /**
     * 页头操作（FEAT-UI-190）：收藏按钮独立，订阅/屏蔽/分享/原网站主页
     * 收进「更多」溢出菜单。
     */
    private fun setupMoreMenu() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                subscribedState = subscriptionRepository.isSubscribed(service, creatorId)
            }
        }
        blockedState = blockedCreatorManager.isCreatorBlocked(service, creatorId)

        binding.btnCreatorMore.setOnClickListener { anchor ->
            hideKeyboard()
            showCreatorMoreMenu(anchor)
        }
    }

    private fun showCreatorMoreMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(
            0, MENU_SUBSCRIBE, 0,
            getString(if (subscribedState) R.string.unsubscribe_from_updates else R.string.subscribe_to_updates)
        )
        // FEAT-SOURCE-SITE-LINK：patreon / fanbox / pixiv 可推导原站主页
        if (CreatorSiteLinks.profileUrl(service, creatorId) != null) {
            popup.menu.add(0, MENU_OPEN_SOURCE, 1, R.string.open_on_source_site)
        }
        popup.menu.add(0, MENU_SHARE, 2, R.string.share_creator)
        popup.menu.add(
            0, MENU_BLOCK, 3,
            getString(if (blockedState) R.string.unblock_creator else R.string.block_creator)
        )
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SUBSCRIBE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewLifecycleOwner.lifecycleScope.launch { toggleSubscriptionSafe() }
                    }
                    true
                }
                MENU_OPEN_SOURCE -> {
                    CreatorSiteLinks.profileUrl(service, creatorId)?.let { openInBrowser(it) }
                    true
                }
                MENU_SHARE -> {
                    showShareDialog()
                    true
                }
                MENU_BLOCK -> {
                    toggleBlockWithConfirm()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewLifecycleOwner.lifecycleScope.launch { toggleSubscriptionSafe() }
    }

    private suspend fun toggleSubscriptionSafe() {
        runCatching { toggleSubscription() }.onFailure { err ->
            android.util.Log.e("CreatorProfileFragment", "toggleSubscribe failed", err)
            Toast.makeText(
                context,
                "subscribe: ${err.javaClass.simpleName} - ${err.message?.take(100)}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun toggleSubscription() {
        val subscribed = subscriptionRepository.isSubscribed(service, creatorId)
        if (subscribed) {
            subscriptionRepository.unsubscribe(service, creatorId)
            updateSubscribeIcon(false)
            Toast.makeText(context, R.string.subscription_removed, Toast.LENGTH_SHORT).show()
        } else {
            val newestPostId = viewModel.uiState.value.posts.firstOrNull()?.id
            subscriptionRepository.subscribe(
                service = service,
                creatorId = creatorId,
                name = viewModel.uiState.value.name.ifEmpty { creatorId },
                lastPostId = newestPostId
            )
            updateSubscribeIcon(true)
            Toast.makeText(context, R.string.subscription_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSubscribeIcon(subscribed: Boolean) {
        subscribedState = subscribed
    }

    /**
     * 分享作者主页（FEAT-CREATOR-SHARE）：复制作者 ID / 复制 Pawchive 主页链接 / 浏览器打开。
     */
    private fun showShareDialog() {
        val options = arrayOf(
            getString(R.string.copy_creator_id),
            getString(R.string.copy_profile_link),
            getString(R.string.open_in_browser)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_creator)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        copyToClipboard("pawchive_creator_id", creatorId)
                        Toast.makeText(context, R.string.id_copied, Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        copyToClipboard("pawchive_creator_link", profileUrl())
                        Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                    }
                    else -> openInBrowser(profileUrl())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 作者网页版主页链接（与站点路由 /{service}/user/{id} 一致）。 */
    private fun profileUrl(): String = "https://pawchive.pw/$service/user/$creatorId"

    private fun copyToClipboard(tag: String, text: String) {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(tag, text))
    }

    private fun openInBrowser(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, R.string.browser_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleBlockWithConfirm() {
        val newBlocked = !blockedCreatorManager.isCreatorBlocked(service, creatorId)
        if (newBlocked) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.block_creator_confirm_title)
                    .setMessage(getString(R.string.block_creator_confirm_message))
                    .setPositiveButton(R.string.block_creator) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            blockedCreatorManager.blockCreator(service, creatorId)
                            blockedState = true
                            Toast.makeText(context, R.string.creator_blocked, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    blockedCreatorManager.unblockCreator(service, creatorId)
                    blockedState = false
                    Toast.makeText(context, R.string.creator_unblocked, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun setServiceLabelColor(serviceName: String) {
        val context = requireContext()
        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colorRes = when (serviceName.lowercase()) {
            "patreon" -> if (isDarkMode) R.color.patreon_text_dark else R.color.patreon_text_light
            "fanbox" -> if (isDarkMode) R.color.fanbox_text_dark else R.color.fanbox_text_light
            else -> if (isDarkMode) R.color.service_text_default_dark else R.color.service_text_default_light
        }
        binding.tvCreatorService.setTextColor(context.getColor(colorRes))
    }

    private fun loadAvatar() {
        val avatarUrl = "https://pawchive.pw/icons/$service/$creatorId"
        binding.ivCreatorAvatar.load(avatarUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_image)
            error(R.drawable.ic_image_off)
        }
    }

    private fun updateLoadMoreButton(hasMore: Boolean) {
        // FEAT-UI-192：加载更多按钮改为列表末尾的页脚，仅有未加载内容时出现
        postAdapter.setFooterVisible(hasMore)
    }

    private fun hideKeyboard() {
        // BUG-CREATOR-SETUP-NPE：onPause 可能先于 onViewCreated 完成触发（如返回手势
        // 快速退出），lateinit 未初始化或视图已销毁时安全返回，不再崩溃
        val view = when {
            ::searchViewCreator.isInitialized -> searchViewCreator
            _binding != null -> _binding!!.root
            else -> return
        }
        val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        view.clearFocus()
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SERVICE = "service"
        private const val ARG_CREATOR_ID = "creator_id"
        private const val MENU_SUBSCRIBE = 1
        private const val MENU_OPEN_SOURCE = 2
        private const val MENU_SHARE = 3
        private const val MENU_BLOCK = 4

        fun newInstance(service: String, creatorId: String): CreatorProfileFragment {
            return CreatorProfileFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVICE, service)
                    putString(ARG_CREATOR_ID, creatorId)
                }
            }
        }
    }
}


