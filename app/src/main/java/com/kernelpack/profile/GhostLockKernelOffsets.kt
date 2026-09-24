package com.kernelpack.profile

/**
 * 上游 GhostLock 源码登记的**逐内核真实偏移**。
 *
 * ### 数据从哪来（可核查）
 *
 * 逐条抓自 `YuKongA/ghostlock-app` 的 `src/kernels/<内核串>/offsets.h`
 * （每档 9 个符号地址）与 `src/kernels/offsets.h` 的 3 个结构体族宏。
 * 抓取脚本与原始 JSON 见工作区 `GhostLock内核偏移/`。
 *
 * ### ⚠️ 可信度：`UPSTREAM`，**不是我方实测**
 *
 * 这些是**上游声称**的数据。按本工程铁律，登记时必须标 `UPSTREAM_TARGET_H`，
 * 界面上不得呈现为 `[实测]`。它们让这些内核**有真实可用的一份偏移表**，
 * 但**不等于**我方在真机上验证过。
 *
 * ### 三族结构体偏移已与我方独立验证的三个来源对上
 *
 * | 族 | pi_lock | cred | tasks | 独立佐证 |
 * |---|---|---|---|---|
 * | `6_1` | 0x924 | 0x838 | 0x550 | `tokay-CP2A.260605.012/target.h`（且 compact_waiter=1） |
 * | `6_6` | 0x90C | 0x820 | 0x550 | `frankel-CP2A.260605.012/target.h` |
 * | `6_12` | 0x9EC | 0x900 | 0x638 | `pyyyc` 荣耀 6.12.38 的 BTF 实测 offsets.json |
 *
 * 三族**互不相同**，再次印证本工程的一条硬结论：
 * **task_struct 布局不能按内核版本外推，必须逐族（甚至逐机型）实测。**
 */
object GhostLockKernelOffsets {

    /** 结构体族名。 */
    enum class StructFamily { F6_1, F6_6, F6_12 }

    /** 三个结构体族的字段偏移（上游 `src/kernels/offsets.h`）。 */
    val STRUCT_FAMILIES: Map<StructFamily, Map<String, Long>> = mapOf(
        StructFamily.F6_1 to mapOf(
            "task_prio" to 132L,
            "task_normal_prio" to 140L,
            "task_sched_task_group" to 840L,
            "task_pi_lock" to 2340L,
            "task_pi_waiters" to 2360L,
            "task_pi_top_task" to 2376L,
            "task_pi_blocked_on" to 2384L,
            "task_pid" to 1584L,
            "task_tgid" to 1588L,
            "task_atomic_flags" to 1520L,
            "task_real_cred" to 2096L,
            "task_cred" to 2104L,
            "task_comm" to 2120L,
            "task_tasks" to 1360L,
            "task_seccomp" to 2304L,
            "compact_waiter" to 1L,
            "mm_struct_sz" to 1024L,
        ),
        StructFamily.F6_6 to mapOf(
            "task_prio" to 132L,
            "task_normal_prio" to 140L,
            "task_sched_task_group" to 840L,
            "task_pi_lock" to 2316L,
            "task_pi_waiters" to 2336L,
            "task_pi_top_task" to 2352L,
            "task_pi_blocked_on" to 2360L,
            "task_pid" to 1560L,
            "task_tgid" to 1564L,
            "task_atomic_flags" to 1496L,
            "task_real_cred" to 2072L,
            "task_cred" to 2080L,
            "task_comm" to 2096L,
            "task_tasks" to 1360L,
            "task_seccomp" to 2280L,
        ),
        StructFamily.F6_12 to mapOf(
            "task_prio" to 148L,
            "task_normal_prio" to 156L,
            "task_sched_task_group" to 1056L,
            "task_pi_lock" to 2540L,
            "task_pi_waiters" to 2560L,
            "task_pi_top_task" to 2576L,
            "task_pi_blocked_on" to 2584L,
            "task_pid" to 1800L,
            "task_tgid" to 1804L,
            "task_atomic_flags" to 1736L,
            "task_real_cred" to 2296L,
            "task_cred" to 2304L,
            "task_comm" to 2320L,
            "task_tasks" to 1592L,
            "task_seccomp" to 2504L,
        ),
    )

    /** 一个内核档位：内核串 + 它的 9 个符号地址 + 归属的结构体族。 */
    data class KernelOffsets(
        val release: String,
        val family: StructFamily,
        /** 上游给出的 pselect 载波字偏移（可为负）。 */
        val pselectWaiterShift: Int?,
        val symbols: Map<String, Long>,
    )

    /** 50 档上游内核偏移（逐条来自其源码目录）。 */
    val KERNELS: List<KernelOffsets> = listOf(
        KernelOffsets(
            release = "6.1.115-android14-11-ga2521ca27699-ab13294383",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33495848L, "off_init_task" to 33420800L, "off_root_task_group" to 35411328L, "off_security_hook_heads" to 22744760L, "off_selinux_blob_sizes" to 22746568L, "off_selinux_enforcing" to 35746768L, "off_slide_boot_id" to 35882072L, "off_slide_loggers_0_1" to 33368344L, "off_slide_nfulnl_logger" to 33368520L),
        ),
        KernelOffsets(
            release = "6.1.118-android14-11-ga3b9c44908dd-ab13320413",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33495656L, "off_init_task" to 33420800L, "off_root_task_group" to 35411328L, "off_security_hook_heads" to 22752408L, "off_selinux_blob_sizes" to 22754216L, "off_selinux_enforcing" to 35746768L, "off_slide_boot_id" to 35882072L, "off_slide_loggers_0_1" to 33368344L, "off_slide_nfulnl_logger" to 33368520L),
        ),
        KernelOffsets(
            release = "6.1.118-android14-11-gca0ef6d17716-ab13624819",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33495656L, "off_init_task" to 33420800L, "off_root_task_group" to 35415424L, "off_security_hook_heads" to 22754104L, "off_selinux_blob_sizes" to 22755912L, "off_selinux_enforcing" to 35750864L, "off_slide_boot_id" to 35886168L, "off_slide_loggers_0_1" to 33368344L, "off_slide_nfulnl_logger" to 33368520L),
        ),
        KernelOffsets(
            release = "6.1.138-android14-11-g0c3d559bcd85-ab14529422",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33561192L, "off_init_task" to 33486336L, "off_root_task_group" to 35485056L, "off_security_hook_heads" to 22769784L, "off_selinux_blob_sizes" to 22771592L, "off_selinux_enforcing" to 35820496L, "off_slide_boot_id" to 35955800L, "off_slide_loggers_0_1" to 33433880L, "off_slide_nfulnl_logger" to 33434056L),
        ),
        KernelOffsets(
            release = "6.1.138-android14-11-g2ecae636cf9b-ab14676408",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33561192L, "off_init_task" to 33486336L, "off_root_task_group" to 35485056L, "off_security_hook_heads" to 22769816L, "off_selinux_blob_sizes" to 22771624L, "off_selinux_enforcing" to 35820496L, "off_slide_boot_id" to 35955800L, "off_slide_loggers_0_1" to 33433880L, "off_slide_nfulnl_logger" to 33434056L),
        ),
        KernelOffsets(
            release = "6.1.138-android14-11-g44bda9e8f6e9-ab13792638",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33561192L, "off_init_task" to 33486336L, "off_root_task_group" to 35485056L, "off_security_hook_heads" to 22767064L, "off_selinux_blob_sizes" to 22768872L, "off_selinux_enforcing" to 35820496L, "off_slide_boot_id" to 35955800L, "off_slide_loggers_0_1" to 33433880L, "off_slide_nfulnl_logger" to 33434056L),
        ),
        KernelOffsets(
            release = "6.1.138-android14-11-g6ab8c9a86a33-ab14396278",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33561192L, "off_init_task" to 33486336L, "off_root_task_group" to 35485056L, "off_security_hook_heads" to 22769848L, "off_selinux_blob_sizes" to 22771656L, "off_selinux_enforcing" to 35820496L, "off_slide_boot_id" to 35955800L, "off_slide_loggers_0_1" to 33433880L, "off_slide_nfulnl_logger" to 33434056L),
        ),
        KernelOffsets(
            release = "6.1.138-android14-11-g965475777129-mi",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 34615112L, "off_init_task" to 34535744L, "off_root_task_group" to 36533504L, "off_security_hook_heads" to 23247352L, "off_selinux_blob_sizes" to 23256928L, "off_selinux_enforcing" to 36868272L, "off_slide_boot_id" to 37003264L, "off_slide_loggers_0_1" to 34482472L, "off_slide_nfulnl_logger" to 34482648L),
        ),
        KernelOffsets(
            release = "6.1.145-android14-11-g09f1c0074ad7-ab14226177",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33692264L, "off_init_task" to 33617408L, "off_root_task_group" to 35616128L, "off_security_hook_heads" to 22849880L, "off_selinux_blob_sizes" to 22851688L, "off_selinux_enforcing" to 35951616L, "off_slide_boot_id" to 36086936L, "off_slide_loggers_0_1" to 33564952L, "off_slide_nfulnl_logger" to 33565128L),
        ),
        KernelOffsets(
            release = "6.1.145-android14-11-g74d1702dab4d-ab14669069",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33692264L, "off_init_task" to 33617408L, "off_root_task_group" to 35616128L, "off_security_hook_heads" to 22855416L, "off_selinux_blob_sizes" to 22857224L, "off_selinux_enforcing" to 35951616L, "off_slide_boot_id" to 36086936L, "off_slide_loggers_0_1" to 33564952L, "off_slide_nfulnl_logger" to 33565128L),
        ),
        KernelOffsets(
            release = "6.1.145-android14-11-geaa643a2c0ee-ab14763719",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33692264L, "off_init_task" to 33617408L, "off_root_task_group" to 35616128L, "off_security_hook_heads" to 22855608L, "off_selinux_blob_sizes" to 22857416L, "off_selinux_enforcing" to 35951616L, "off_slide_boot_id" to 36086936L, "off_slide_loggers_0_1" to 33564952L, "off_slide_nfulnl_logger" to 33565128L),
        ),
        KernelOffsets(
            release = "6.1.157-android14-11-gbd23337e42e7-ab14791245",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33757864L, "off_init_task" to 33683008L, "off_root_task_group" to 35685760L, "off_security_hook_heads" to 22865016L, "off_selinux_blob_sizes" to 22866824L, "off_selinux_enforcing" to 36021280L, "off_slide_boot_id" to 36156568L, "off_slide_loggers_0_1" to 33630496L, "off_slide_nfulnl_logger" to 33630672L),
        ),
        KernelOffsets(
            release = "6.1.162-android14-11-g5e8b0cffebd1-ab15202165",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33757416L, "off_init_task" to 33682496L, "off_root_task_group" to 35689856L, "off_security_hook_heads" to 22876472L, "off_selinux_blob_sizes" to 22878280L, "off_selinux_enforcing" to 36025416L, "off_slide_boot_id" to 36160728L, "off_slide_loggers_0_1" to 33629984L, "off_slide_nfulnl_logger" to 33630160L),
        ),
        KernelOffsets(
            release = "6.1.162-android14-11-g752d9c17787d-ab15574904",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33757416L, "off_init_task" to 33682496L, "off_root_task_group" to 35689856L, "off_security_hook_heads" to 22878904L, "off_selinux_blob_sizes" to 22880712L, "off_selinux_enforcing" to 36025416L, "off_slide_boot_id" to 36160728L, "off_slide_loggers_0_1" to 33629984L, "off_slide_nfulnl_logger" to 33630160L),
        ),
        KernelOffsets(
            release = "6.1.162-android14-11-gce140c0e5bf5-ab15450923",
            family = StructFamily.F6_1,
            pselectWaiterShift = 1,
            symbols = mapOf("off_init_cred" to 33757416L, "off_init_task" to 33682496L, "off_root_task_group" to 35689856L, "off_security_hook_heads" to 22878584L, "off_selinux_blob_sizes" to 22880392L, "off_selinux_enforcing" to 36025416L, "off_slide_boot_id" to 36160728L, "off_slide_loggers_0_1" to 33629984L, "off_slide_nfulnl_logger" to 33630160L),
        ),
        KernelOffsets(
            release = "6.12.23-android16-5-g16e473de48a3-abogki462654244-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37825128L, "off_init_task" to 37736192L, "off_root_task_group" to 40035712L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25437416L, "off_selinux_enforcing" to 40347064L, "off_slide_boot_id" to 40483176L, "off_slide_loggers_0_1" to 37691640L, "off_slide_nfulnl_logger" to 37691824L),
        ),
        KernelOffsets(
            release = "6.12.23-android16-5-g75e9b1c7ae7c-abogki463945075-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37825128L, "off_init_task" to 37736192L, "off_root_task_group" to 40035712L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25441512L, "off_selinux_enforcing" to 40347064L, "off_slide_boot_id" to 40483176L, "off_slide_loggers_0_1" to 37691640L, "off_slide_nfulnl_logger" to 37691824L),
        ),
        KernelOffsets(
            release = "6.12.23-android16-5-g82efd98459a2-ab14457512-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37759592L, "off_init_task" to 37670656L, "off_root_task_group" to 39961984L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25359592L, "off_selinux_enforcing" to 40273136L, "off_slide_boot_id" to 40409192L, "off_slide_loggers_0_1" to 37626080L, "off_slide_nfulnl_logger" to 37626264L),
        ),
        KernelOffsets(
            release = "6.12.23-android16-5-ga8f88ad96df3-ab13929693-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37759592L, "off_init_task" to 37670656L, "off_root_task_group" to 39961984L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25351400L, "off_selinux_enforcing" to 40273136L, "off_slide_boot_id" to 40409192L, "off_slide_loggers_0_1" to 37626080L, "off_slide_nfulnl_logger" to 37626264L),
        ),
        KernelOffsets(
            release = "6.12.23-android16-5-gb2a876903b49-ab14541642-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37759592L, "off_init_task" to 37670656L, "off_root_task_group" to 39961984L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25363688L, "off_selinux_enforcing" to 40273136L, "off_slide_boot_id" to 40409192L, "off_slide_loggers_0_1" to 37626080L, "off_slide_nfulnl_logger" to 37626264L),
        ),
        KernelOffsets(
            release = "6.12.23-android16-5-gf1bdb13583da-ab13761046-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37693992L, "off_init_task" to 37605056L, "off_root_task_group" to 39892352L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25339112L, "off_selinux_enforcing" to 40203496L, "off_slide_boot_id" to 40339560L, "off_slide_loggers_0_1" to 37560536L, "off_slide_nfulnl_logger" to 37560720L),
        ),
        KernelOffsets(
            release = "6.12.30-android16-5-g6e872b4863d6-ab13847919-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37825648L, "off_init_task" to 37736192L, "off_root_task_group" to 40027520L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25425128L, "off_selinux_enforcing" to 40338616L, "off_slide_boot_id" to 40474728L, "off_slide_loggers_0_1" to 37691616L, "off_slide_nfulnl_logger" to 37691800L),
        ),
        KernelOffsets(
            release = "6.12.38-android16-5-g1d46253471dd-ab15048002-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37891184L, "off_init_task" to 37801728L, "off_root_task_group" to 40101760L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25466088L, "off_selinux_enforcing" to 40412880L, "off_slide_boot_id" to 40548968L, "off_slide_loggers_0_1" to 37757160L, "off_slide_nfulnl_logger" to 37757344L),
        ),
        KernelOffsets(
            release = "6.12.38-android16-5-g3c4da6410bcb-ab13872285-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37825648L, "off_init_task" to 37736192L, "off_root_task_group" to 40027520L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25437416L, "off_selinux_enforcing" to 40338632L, "off_slide_boot_id" to 40474728L, "off_slide_loggers_0_1" to 37691616L, "off_slide_nfulnl_logger" to 37691800L),
        ),
        KernelOffsets(
            release = "6.12.38-android16-5-g665eafb62659-ab14778838-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37891184L, "off_init_task" to 37801728L, "off_root_task_group" to 40097152L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25466088L, "off_selinux_enforcing" to 40408272L, "off_slide_boot_id" to 40544360L, "off_slide_loggers_0_1" to 37757160L, "off_slide_nfulnl_logger" to 37757344L),
        ),
        KernelOffsets(
            release = "6.12.38-android16-5-g74ad46052215-ab14494108-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37891184L, "off_init_task" to 37801728L, "off_root_task_group" to 40097152L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25466088L, "off_selinux_enforcing" to 40408272L, "off_slide_boot_id" to 40544360L, "off_slide_loggers_0_1" to 37757160L, "off_slide_nfulnl_logger" to 37757344L),
        ),
        KernelOffsets(
            release = "6.12.38-android16-5-g844001fb8721-ab14552068-4k",
            family = StructFamily.F6_12,
            pselectWaiterShift = 0,
            symbols = mapOf("off_init_cred" to 37891184L, "off_init_task" to 37801728L, "off_root_task_group" to 40097152L, "off_security_hook_heads" to 0L, "off_selinux_blob_sizes" to 25466088L, "off_selinux_enforcing" to 40408272L, "off_slide_boot_id" to 40544360L, "off_slide_loggers_0_1" to 37757160L, "off_slide_nfulnl_logger" to 37757344L),
        ),
        KernelOffsets(
            release = "6.6.102-android15-8-gab8eb70a71b8-ab14350911-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34735944L, "off_init_task" to 34660992L, "off_root_task_group" to 36730240L, "off_security_hook_heads" to 23486064L, "off_selinux_blob_sizes" to 23487912L, "off_selinux_enforcing" to 36998832L, "off_slide_boot_id" to 37133992L, "off_slide_loggers_0_1" to 34611624L, "off_slide_nfulnl_logger" to 34611800L),
        ),
        KernelOffsets(
            release = "6.6.102-android15-8-gb01b41c2647c-ab15574720-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34735944L, "off_init_task" to 34660992L, "off_root_task_group" to 36730240L, "off_security_hook_heads" to 23490832L, "off_selinux_blob_sizes" to 23492680L, "off_selinux_enforcing" to 36998832L, "off_slide_boot_id" to 37133992L, "off_slide_loggers_0_1" to 34611624L, "off_slide_nfulnl_logger" to 34611800L),
        ),
        KernelOffsets(
            release = "6.6.102-android15-8-gfe76d1bc97fd-ab14689815-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34735944L, "off_init_task" to 34660992L, "off_root_task_group" to 36730240L, "off_security_hook_heads" to 23489744L, "off_selinux_blob_sizes" to 23491592L, "off_selinux_enforcing" to 36998832L, "off_slide_boot_id" to 37133992L, "off_slide_loggers_0_1" to 34611624L, "off_slide_nfulnl_logger" to 34611800L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-g21be90ecfb5e-ab15480137-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34867016L, "off_init_task" to 34792064L, "off_root_task_group" to 36866432L, "off_security_hook_heads" to 23610744L, "off_selinux_blob_sizes" to 23612592L, "off_selinux_enforcing" to 37135072L, "off_slide_boot_id" to 37270232L, "off_slide_loggers_0_1" to 34742704L, "off_slide_nfulnl_logger" to 34742880L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-g2e6b9c3812c5-ab15114928-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34801480L, "off_init_task" to 34726528L, "off_root_task_group" to 36795776L, "off_security_hook_heads" to 23572120L, "off_selinux_blob_sizes" to 23573968L, "off_selinux_enforcing" to 37064416L, "off_slide_boot_id" to 37199576L, "off_slide_loggers_0_1" to 34677168L, "off_slide_nfulnl_logger" to 34677344L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-g608a629fedf7-ab15154340-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34801480L, "off_init_task" to 34726528L, "off_root_task_group" to 36795776L, "off_security_hook_heads" to 23572216L, "off_selinux_blob_sizes" to 23574064L, "off_selinux_enforcing" to 37064416L, "off_slide_boot_id" to 37199576L, "off_slide_loggers_0_1" to 34677168L, "off_slide_nfulnl_logger" to 34677344L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-g93e223c276e7-abogki500782043-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34933832L, "off_init_task" to 34858880L, "off_root_task_group" to 36930944L, "off_security_hook_heads" to 23651056L, "off_selinux_blob_sizes" to 23652904L, "off_selinux_enforcing" to 37204512L, "off_slide_boot_id" to 37339672L, "off_slide_loggers_0_1" to 34809504L, "off_slide_nfulnl_logger" to 34809680L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-gbf8cd367de7a-ab15314822-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34867016L, "off_init_task" to 34792064L, "off_root_task_group" to 36866432L, "off_security_hook_heads" to 23610040L, "off_selinux_blob_sizes" to 23611888L, "off_selinux_enforcing" to 37135072L, "off_slide_boot_id" to 37270232L, "off_slide_loggers_0_1" to 34742704L, "off_slide_nfulnl_logger" to 34742880L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-gc44b714366cc-abogki519650608-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34932616L, "off_init_task" to 34857664L, "off_root_task_group" to 36931968L, "off_security_hook_heads" to 23619320L, "off_selinux_blob_sizes" to 23621168L, "off_selinux_enforcing" to 37200760L, "off_slide_boot_id" to 37335920L, "off_slide_loggers_0_1" to 34808264L, "off_slide_nfulnl_logger" to 34808440L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-ge56cf6b09cca-ab15511674-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34867016L, "off_init_task" to 34792064L, "off_root_task_group" to 36866432L, "off_security_hook_heads" to 23612664L, "off_selinux_blob_sizes" to 23614512L, "off_selinux_enforcing" to 37135072L, "off_slide_boot_id" to 37270232L, "off_slide_loggers_0_1" to 34742704L, "off_slide_nfulnl_logger" to 34742880L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-ge58033dc8ea6-abogki498046332-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34933832L, "off_init_task" to 34858880L, "off_root_task_group" to 36930944L, "off_security_hook_heads" to 23649040L, "off_selinux_blob_sizes" to 23650888L, "off_selinux_enforcing" to 37204512L, "off_slide_boot_id" to 37339672L, "off_slide_loggers_0_1" to 34809504L, "off_slide_nfulnl_logger" to 34809680L),
        ),
        KernelOffsets(
            release = "6.6.118-android15-8-gebdfad32d749-ab15099304-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34801480L, "off_init_task" to 34726528L, "off_root_task_group" to 36795776L, "off_security_hook_heads" to 23570264L, "off_selinux_blob_sizes" to 23572112L, "off_selinux_enforcing" to 37064416L, "off_slide_boot_id" to 37199576L, "off_slide_loggers_0_1" to 34677168L, "off_slide_nfulnl_logger" to 34677344L),
        ),
        KernelOffsets(
            release = "6.6.30-android15-8-g54dcbfbef792-ab12368803-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34008360L, "off_init_task" to 33934016L, "off_root_task_group" to 35964160L, "off_security_hook_heads" to 23073936L, "off_selinux_blob_sizes" to 23075784L, "off_selinux_enforcing" to 36219560L, "off_slide_boot_id" to 36354696L, "off_slide_loggers_0_1" to 33886632L, "off_slide_nfulnl_logger" to 33886808L),
        ),
        KernelOffsets(
            release = "6.6.77-android15-8-g4a507830d890-ab13636293-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34473288L, "off_init_task" to 34398848L, "off_root_task_group" to 36451712L, "off_security_hook_heads" to 23376560L, "off_selinux_blob_sizes" to 23378408L, "off_selinux_enforcing" to 36720264L, "off_slide_boot_id" to 36855416L, "off_slide_loggers_0_1" to 34349480L, "off_slide_nfulnl_logger" to 34349656L),
        ),
        KernelOffsets(
            release = "6.6.77-android15-8-g63ce7556864c-ab13994517-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34538824L, "off_init_task" to 34464384L, "off_root_task_group" to 36517248L, "off_security_hook_heads" to 23381168L, "off_selinux_blob_sizes" to 23383016L, "off_selinux_enforcing" to 36785840L, "off_slide_boot_id" to 36920992L, "off_slide_loggers_0_1" to 34415016L, "off_slide_nfulnl_logger" to 34415192L),
        ),
        KernelOffsets(
            release = "6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34538824L, "off_init_task" to 34464384L, "off_root_task_group" to 36521344L, "off_security_hook_heads" to 23393328L, "off_selinux_blob_sizes" to 23395176L, "off_selinux_enforcing" to 36790120L, "off_slide_boot_id" to 36925272L, "off_slide_loggers_0_1" to 34415040L, "off_slide_nfulnl_logger" to 34415216L),
        ),
        KernelOffsets(
            release = "6.6.89-android15-8-g0889fe95bb10-ab14402178-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34669896L, "off_init_task" to 34595456L, "off_root_task_group" to 36656512L, "off_security_hook_heads" to 23469752L, "off_selinux_blob_sizes" to 23471600L, "off_selinux_enforcing" to 36925088L, "off_slide_boot_id" to 37060248L, "off_slide_loggers_0_1" to 34546088L, "off_slide_nfulnl_logger" to 34546264L),
        ),
        KernelOffsets(
            release = "6.6.89-android15-8-g096cdb6ecefc-ab14358676-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34604360L, "off_init_task" to 34529920L, "off_root_task_group" to 36590976L, "off_security_hook_heads" to 23403280L, "off_selinux_blob_sizes" to 23405128L, "off_selinux_enforcing" to 36859552L, "off_slide_boot_id" to 36994712L, "off_slide_loggers_0_1" to 34480552L, "off_slide_nfulnl_logger" to 34480728L),
        ),
        KernelOffsets(
            release = "6.6.89-android15-8-g42db9ecb036b-ab14487600-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34669896L, "off_init_task" to 34595456L, "off_root_task_group" to 36656512L, "off_security_hook_heads" to 23469720L, "off_selinux_blob_sizes" to 23471568L, "off_selinux_enforcing" to 36925088L, "off_slide_boot_id" to 37060248L, "off_slide_loggers_0_1" to 34546088L, "off_slide_nfulnl_logger" to 34546264L),
        ),
        KernelOffsets(
            release = "6.6.89-android15-8-g8e4be6b47e40-ab14134548-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34604360L, "off_init_task" to 34529920L, "off_root_task_group" to 36590976L, "off_security_hook_heads" to 23402192L, "off_selinux_blob_sizes" to 23404040L, "off_selinux_enforcing" to 36859552L, "off_slide_boot_id" to 36994712L, "off_slide_loggers_0_1" to 34480552L, "off_slide_nfulnl_logger" to 34480728L),
        ),
        KernelOffsets(
            release = "6.6.89-android15-8-gb99b4586a3ee-ab13754593-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34538824L, "off_init_task" to 34464384L, "off_root_task_group" to 36525440L, "off_security_hook_heads" to 23396976L, "off_selinux_blob_sizes" to 23398824L, "off_selinux_enforcing" to 36794016L, "off_slide_boot_id" to 36929176L, "off_slide_loggers_0_1" to 34415016L, "off_slide_nfulnl_logger" to 34415192L),
        ),
        KernelOffsets(
            release = "6.6.89-android15-8-gf4dc45704e54-abogki446052083-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34736712L, "off_init_task" to 34662272L, "off_root_task_group" to 36726144L, "off_security_hook_heads" to 23480648L, "off_selinux_blob_sizes" to 23482496L, "off_selinux_enforcing" to 36999392L, "off_slide_boot_id" to 37134552L, "off_slide_loggers_0_1" to 34612888L, "off_slide_nfulnl_logger" to 34613064L),
        ),
        KernelOffsets(
            release = "6.6.92-android15-8-g3637f4904cf5-ab13944661-4k",
            family = StructFamily.F6_6,
            pselectWaiterShift = -2,
            symbols = mapOf("off_init_cred" to 34670400L, "off_init_task" to 34595456L, "off_root_task_group" to 36660608L, "off_security_hook_heads" to 23466192L, "off_selinux_blob_sizes" to 23468040L, "off_selinux_enforcing" to 36929200L, "off_slide_boot_id" to 37064360L, "off_slide_loggers_0_1" to 34546088L, "off_slide_nfulnl_logger" to 34546264L),
        ),
    )

    /** 按内核串精确取（**不做前缀推断** —— 未登记的版本就是取不到）。 */
    fun byRelease(release: String): KernelOffsets? =
        KERNELS.firstOrNull { it.release == release }

    /** 某族的字段偏移；族未知返回 null，不猜。 */
    fun structFields(family: StructFamily): Map<String, Long> =
        STRUCT_FAMILIES[family].orEmpty()

    /** 覆盖的小版本（如 `6.6.118`），数值序。 */
    val MINOR_VERSIONS: List<String> = KERNELS
        .map { it.release.substringBefore('-') }
        .distinct()
        .sortedWith(Comparator { a, b ->
            val pa = a.split('.').mapNotNull(String::toIntOrNull)
            val pb = b.split('.').mapNotNull(String::toIntOrNull)
            var r = 0
            for (i in 0 until minOf(pa.size, pb.size)) { r = pa[i].compareTo(pb[i]); if (r != 0) break }
            if (r != 0) r else pa.size.compareTo(pb.size)
        })
}
