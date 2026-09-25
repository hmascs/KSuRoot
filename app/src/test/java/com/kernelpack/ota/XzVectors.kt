package com.kernelpack.ota

/**
 * XZ 解码的交叉验证向量 —— **由真实 `xz` 生成，请勿手改**。
 *
 * 生成方式（容器内）：
 * ```
 * xz -9 -k -f mixed.bin
 * xz --check=crc32 -9 -k -f small.bin
 * xz --block-size=16384 -9 -k -f multi.bin
 * ```
 * 这里只内嵌**压缩后的字节**与**解码后的 sha256** —— 期望输出最大有 262 KiB，
 * 塞进源码不合适（同 `com.kernelpack.boot.Lz4Vectors` 的做法）。
 *
 * 为什么必须用真实 xz 产物：XZ 的 range coder 没有"部分正确"可言 ——
 * 概率模型差一位，后面全盘皆错。自己造一份"看起来像 XZ"的字节证明不了任何东西。
 */
internal object XzVectors {

    data class Vector(
        val name: String,
        /** base64 的完整 `.xz` 流 */
        val compressedB64: String,
        /** 解码后的字节数（同时是传给 XzDecoder.decode 的 maxOutput） */
        val decodedSize: Int,
        /** 解码后的 sha256，由 xz 侧算出 */
        val decodedSha256: String,
        val note: String,
    )


    /** 单块流：重复块 + 伪随机 + 逐块计数器（同时压到 literal 与 match）（解码后 262400 字节） */
    val MIXED = Vector(
        name = "mixed",
        compressedB64 =
            "/Td6WFoAAATm1rRGAgAhARwAAAAQz1jM5AD/ETpdAHO7mJii1QCcLgBZflNONXaU10QBzQWLwxezktutLmrncQtjFk8yJVuvmU/9" +
            "h4rG0J6mz1wKvkWQ0gf7tYXQN5jR1fz8LUspXjR/W2O+3K500djMBWC0nsrZRead8yB9O0wPcQvk8CnM1o9IaGDReOwXbBh8W7DK" +
            "OvyXCESBwkOT7agkNWCrwBG7ZNDWTILPNcYc3jzLFg2Cb46XB2PV/jueW+zq8hASG1nigTEPu4ou3VYBdinKnub+gyBfAG1gEC/p" +
            "KPrpswjPOtOS0P42cJ8LsFLm4r20DQWYvIe9JfKAGJL/8yYDoNi8mbqLR/vA4E8pvo5flKJuwAdtyaAPFQU0FUN8LK/CNiWAjrqP" +
            "c3z3rtUGXoRTqs3n0OxRrfcGdg4uUwPgaTkKmpIw83uaTcexwS2cC4MVYtmkjeyRqPLDtYk8wpt1KZkUYsZANFJezM5vo6YmAVFb" +
            "7yO2wTPYLF7B/X2QPw9Gx2CjjgOcuBTexWv5bRK2ICDTELrLQAwznMI7Hxhll5vLhHyQYduSjx3HoGpJVv0DD7sfwiBiPVFKGLW5" +
            "oO7+FgIq+SKH0ChYmGywvkIFxq0ioEZj7bKnb31y9HGw0XVtaCRfp9ddG1Kyxrh7gir5GjN5jSSASvVuPCc9yUea/Am110QvWXFA" +
            "564uog4yRWCl/LY6d4dW8G63k1Ocnpo20G2WI1YUz4CpgdW9lBtaMFnFGWvrWHX7lGOFTyuJ/d9YPPvtG39UwZAZ77DQNXZNhxMR" +
            "GaKm1YZf8lmeoD+OrarFb9HQdpFw/wlScVoxnBLCDn29uB99gBaU5ThQYA7lFL0LamHxpk7bS/TRwaYjxO/0bzO7qzhXP9CDVE6r" +
            "1Vu1JsmqNHqnlQnOWVlE9hxTdDQqo+aHy9J1I+QiwuG55DivzVJw3vxe/DgqH5RTRIn9bcLfeW3qYp3aI8xYt4OzqGtzSVdqyvqa" +
            "Ni8uAsiR4opnr3xFedYYsgVTuNSYUcBS/b5eKD5IAabORqa2SxZ0XeKjx1eqaCRnf7sCBkTP6Fn82XwKNaFQ1S+X+ODC/bPIRFTo" +
            "3BrmpYV5jZZa7xXiTmiSdyb9x5/gFjkKZHxuV4IS8lI5bDdcStaCPLGyOjf1sSf2peIHMHmX7B5hx1vQVZaSc/NoW6XuJgBRv+uu" +
            "9xceqYsbEjBX8BLmJQp0f/Q+wAi1vaz24/iS+TrRfoM8vUWcd3pFXXnq5rm7fLHaCk4bMh6MM5H/Oe1Fmqb0mkQB1CUqwHtiPeAq" +
            "FTZbyorwmpyOWABhysNrMGRMB4nbTjwr+g0zL7yUFLUdR/U32jW9kTQ/LKwDBPmBmOzWQBgCR2ZwdMN2my1RqBd4T+PhCbM3bKAv" +
            "dU6ATaSmRD/G/9cAx3O5ypPv1DORs1r0Yczq023DzvMw/HwcZgIR6gj2WtGwedtey+WkaIk3xZ7RcF+g+KLcymPzfFGMcGoBuixB" +
            "YN7N6b8Zj2lsa7R8oNlSt0KoWQsU+xWj9CdVRUxnuG/p0ESE9pBItFR5xfMle+4V7zZ+AH0QNFdFP2knrHAjgl904jmo7BMCZtMp" +
            "moYVGJMFuK5QRPCDaaKXm/5lfQcuv78NhqV3m2BiRsyBOSWXq5VnXsOUZPBpykfOGfWevswzk6HqiEebrJr+0pk90Kjn/U9yKrXA" +
            "8WD0jlbCRSkHzN77PaNdv8d05+CcQ6XLf3Pr1j0SR4hQ3auCBJWH9EE63Kt9kzFocxuYcKhlaDkiz6yBbxC497REprjL0Ffx7L5a" +
            "3vZ2/7CQXTGT1UEgAKbzhOQ4RyygvgitxsOZehDFqkd8zHRoRRu4uvnPs3ulgRAkmj12KfonXHcLIT//HMIKCP4hBvg2F8f6nERl" +
            "+F6MdYbDA3cqw1Myd90eULPbRwzVFZhE+1H0I+BZha+0UItlfZWqzMk56wnHQGJ2Fn+9PqANjF6QsIhLYYqRG8jUtKETzreKIV8C" +
            "J2yI/stn35jgrS88QpxD1R85JrhDcoLybKUt8FEPuaNa16aODQnJ6xJGNatg1DVkwWgZrjtG3a/bf/RTwE+Q60O3iA8s9j45hV41" +
            "9YJOKqC/A/mjMlqdX/HKLpV7a65AivZywG2znGfLF+CIAyMfyM0mRZpcusoBAEvmtwoY4vBV8giS2XqyqdBTnfz86uHP2Amq2led" +
            "qSJkOJQ9O9AEmjFVYP6Fx0vPV6pE9onHztUZt+KMx2eBn5N5MI5DTd8N3sKL4bRGyqL8BSl8bCGOKdLCQGJa8nuhbflmHxFmRsyb" +
            "7r7EAwIv4Wq2GCl9Gh+yc+DFnD2bYcHX8hykOAFlvnEESU1AyM852YAeWnKyFD69EdjaAvqCAFQAp/9BGrRlwVObhNesgruI0iHL" +
            "XIV3INKUMxb78MRWPz1FWipzQcviqMw5M/TdD4F2HpwzNYBQx5W8q0lbxK8lCJxY53NO7j6EcsmYY7SZKW954hqT9HTUkDqSDcIG" +
            "T2I5/zCe5nfpohHvZjlj77cGuia9q9/wQr+/XU9SU249qE/AHFQCc/DIje8bp75iaCWzxqrLQ9boWYf/213pFQwP5jnc+gP/cn4N" +
            "AbiD0CbFpSmn4fvlJwan93MIHVC1OgJKd5D9MYaS6uAyzabqUobokY6x9MiQN0rY3FLFdXbZNZ7aVWpvdrvx1cDAh3YsCfDd1WEu" +
            "K0Ejc8F2wJBLXQRVeAo+WbyJKSDMGk1D/Y23iSV1gZ4+WdQzAlQ/T8+1zT1Vi5F/GcS1aBZbBav2w6C16swLlL1uFxDSGvGzUQXv" +
            "pqmXwnZlKF45K2MlpTsmC03E+hX13dH6S07F4hAVX9H1Yk1Bh8jZNlHegTBtodckC25fmMLomTfV6rleMJxU7V1GL0XVJSFoY8vj" +
            "PeDcgwJ53YxOUop60lIlw40cclvTk6O5z2EzJYmYSv9sJgDbikjSUAFgDuZwCPfsQZ+DQs/HZ67Xcc/yy0CdpF5sYDe6hJSFpHJD" +
            "ORUm8Ud8DOJvB1Lx4BVTvD7ZltmaKsSUb6l1b2EsfxSNb6yl0ThjmIrJ9d/dDa9Tb9Xj+bH9aE8vfA1KT2SJ29CrxW74Xp6OgI6U" +
            "ErhkpcVaTIJpB5udnEL3XDvttON43FvGx1A9gclM701UXMmc3pqb3p5+u32hYLDqAFo+4mNEsERcDdufMuHE/td2MOI+ac/cjsG9" +
            "1Klwi0e/S8NltKQmaRMm4IvHiUhs3Q1LvMr4u1Mf8PiVNGnUi1PLGYZy/xYOjbRBDgfW6XrDzUE7BKF7wcfGvKW5FHrQq8mFv/fd" +
            "VNsDnjDA8lM3wVo0pjxkDUsTyRIz0BgFXHxJaotzYiEN7yZwxvsXBr7UizFbVxMP2rF7kQOwVbgFWQI0bW+psuV+zpV3ESzgH3J3" +
            "z7RtYH6OlTuM6PPBqdt4pCq+Y7bTf0/f1QEcngw3rkzPDi2mvl6WgCTlhFUjiTJ3vEiOcZMRG1O85fjqfAMaIWtoMoQtyYLgquOw" +
            "aBgiWq1igaT8POVHPW/bG5LWVhhgRSajWUCz2MP85N43Z79mRVtmhrQDeuRhaqdUBRsaJpFWv8cEbmVLdrHh0dazpojwfkGk1ulT" +
            "+bN/3o0lFnbD5mhvxE+q4ooxC1ZfCSlJd/5L9lfYAS669/K6R8EE2biruAma1VTmkebomF7j4MVpRWZBx01NPtFmYsJzYN7zKiQo" +
            "djxiEy3i9zIKkIuQtFX2fHcSwDZ0g4nR2Vm6YP7ZMz17XClCY8DtiQ8NEAX1s8NMnSp0b2e9jTyodD4gg7I0znWmG67o6/LWpssG" +
            "FGG9ukQcV7IYNo913y4es8e4P9pUgWWaR8FGEp1lez3GSo+5DpmejhKiaCDD3wWrs5owhWv3jmQkck2Lop8Gvqx2J8x5sZV3w2nV" +
            "P75DIiJgN18LgUoO4YBQ8dlWbZAPmnXxUI+c9pv4Pw5ngjyaiq/c+RTpw5vSs0dYkH0LyHvMlNGboNmpaXbkxlRfeZCZztwttnjL" +
            "WOl6fUJXguvo9IeGz8/EcW2e2cX/7pqlEsQs/z0OwdvZRaJSMONfSFyTrHnhVgrxMNe0m46QsmwMq5440fdv7b4SLYJVbZXoOE3J" +
            "8ddtpKr3iqPFaT0U7FcvLFDLX6vJD+bR+9okh0DueZu49bL23s5N3QtdjgsoS4BYrlDLLmaLOkqT6g77UvbZMV/smMicOaQ25as2" +
            "Ch7z3iRzHFxCXdSG2LCDZLC7uoys7wli88FlOX6dKX64+NjDmYuNFF6doQT0YFqMJb2iuGUOhPAqPOSQ1vp38nLwSnfhck0aNyI3" +
            "XoltEEjierFBZzH0+/tSxSexlBN2b+0Z4M5xj0Igca3fM2QxSuXXzrc878C+v6xob+0JBwpWIUiyyv/wjs+m9Sz3s74EA3jo7OJu" +
            "gzaFwhryPyFQQcFTbmDSNCeaA4Ql4J3sOq298AaG+5vyrs5lGyMy2lFnAVOiJE7+qK6LbTD5kIALFGustiQn0PZgDXmfbHCInRQQ" +
            "jozI9/rNsOWmuFIQkyi+0+qFh8D/gxyfVqP98LKljwIGzDO0TYp5s2IVr2qmSoa5w5Qz4+pivwFo1nb8/Bv8zCxWfVhcB5O+V5m8" +
            "RQGtfk3JrCfgZ2+zavXO69mA1UFcWv4ErPRdxBSE3ANA9rVBHumZi3Zs1WqLPQsl8Kt0fYA/vi1nxAKFL+NuJ2vvA8eOfRtvqW4h" +
            "cgMglLlq40/Cs1Cd2rlKD3aNJVk4uvoEJpsdtwW5QhGU2EVwGjSWLNY5lC/NWPaKY3DMtsFPic28qVDMzVfhGaDnHaCaSGfHVmML" +
            "86PqY0yjVDtCLytUSpEnaOTyoouQIlKmJI2lB57fHTvPU7G6zuoktid3aJ9UgQqyHVT37oZHu+GaEmvL6TE2G0s9QEZYbnqWPe3F" +
            "hM9kgXERYAFCQJMYV/ovBTmJO1pFunSpZnoZzMiCaWvf6+jvswEve6PkMR9gl9kh0Fk02LVAAz/t/THetSLF5D/tXcr+plHeuv6k" +
            "uKEAsx2BP1S+WoSSLcDMYr2E0qN+gasSt9MWw7RNewBj3o7tzh/neiD4O4LMXiNSjrFoQFiTn0XNbFWvlsZfisllByv0g8/xc3HK" +
            "mFfZ94GY9rTKJJv8xsDGQJVMUyGV3Dv8u1zRziiZqymvbiRVRrXmUyaAieBdiBu14UO6G20ge2j+iXMLE9so0qs5YQBpTKcumu2F" +
            "PMBwJ1Cro34U6vCbJlNIvBKaUTsZBTq6zn/oILGOScsFkW8iCMKWxCYj41jPktO5X3LofRaSU5Gy9SawyIigPBWRdf/jMZyUW0w7" +
            "3PA539isi/ji2njiXX7IokCjojxKpryCM2uQC51PF8K/NDAYzce4+pG9Ye//xLLL8HwaGoS0FuK4z8/3+P0mcRDvHnaYZukqPqaT" +
            "xIZvV/SWY7j/agqqT4y3tXdWTUnZHwBTnHKiyh92KR5HXsO5RT7pBZX1jK81kzkUF1CzMb3rX82f8XG79r44U0pD8KnhmxRkQUCY" +
            "NA0BWW+/TeRGI6tryL/rQqWuklY72Bn/crZAmcAcoo3Ok1fE/CqRcZjgd6YoUeKpnst4zVZ2eZ1zsz7Y16IOqF9z7BBlyLN+7F0z" +
            "TVFavTR5TS4O87VYG6pZNAOZrBlfqjY93hG2r9ntG9fMsK0hbx11ot+i2inWmR8j4vtgZlvIpEVtZoo1dkqWgM+Orp4lEqcWPYy7" +
            "aNJ10hwXMD0rOHRY6j7VUUfB1lr0Qa2mPem22LhQgy975X0YbvR+EpfMdpVJoOpegL+11CUfB9WHJu+q47do3Xh/tE1em2j1Dpm9" +
            "WnuA3QiIfxezWpyBEAiWwL35tcvrTai/KTLl1AdZq9jZ2qEDoVXnpn4Fhq/juT03uio8cSdfWyarymoHTJpxaDHeB0qjwPJCspem" +
            "yji6yHrFP6Ar9Ea1aZrEgAAAAMaUw4ply+VQAAHWIoCCEAAPPBn5scRn+wIAAAAABFla"
            ,
        decodedSize = 262400,
        decodedSha256 = "5b47026b9a3ea8859fb726e14a034b7c7e8fc349c1cea19dcad477b8eb39d731",
        note = "单块流：重复块 + 伪随机 + 逐块计数器（同时压到 literal 与 match）",
    )

    /** 纯重复 64 字节块（长 match）（解码后 131072 字节） */
    val REPETITIVE = Vector(
        name = "rep",
        compressedB64 =
            "/Td6WFoAAATm1rRGAgAhARwAAAAQz1jM4f//AJVdAAAAUlAKhPmbsoAhqWnWJ+A+BlpfBI1T1AS6OVcFCcFVJN6duHFZMWChn/lv" +
            "SXPyyOqMuhqLKWkhgP4zg3ziNw/Ru0aKX2AO93YQ68LZszYjw+dlr8eOBam+I9T4RaA+JcWKYqRfbK4pcg8ChLwoAItRhLv+aaGT" +
            "Fp/uY3V14zA4mVRZ314BNs4L/Xrile9BYWe3LGUAAAAAACPF5Xc9XeHhAAGxAYCACAAOGhbrscRn+wIAAAAABFla"
            ,
        decodedSize = 131072,
        decodedSha256 = "3f9a788bff273ae64f276ce772375abda37960cf89c7adb3c558e16c25856374",
        note = "纯重复 64 字节块（长 match）",
    )

    /** 小样本，xz --check=crc32（与默认 crc64 走不同的 check 分支）（解码后 2800 字节） */
    val CRC32_CHECK = Vector(
        name = "small",
        compressedB64 =
            "/Td6WFoAAAFpIt42AgAhARwAAAAQz1jM4ArvADNdADOaCkR1WQ+2sPMiRz7ShJ5roq9cZiAiQJ9vAFmHTvN5xdNwzPV0r0/JlT/G" +
            "aAf2Io0AAAAAKptzBwABS/AVAAAAgtk5iT4wDYsCAAAAAAFZWg=="
            ,
        decodedSize = 2800,
        decodedSha256 = "c6dfbcfed050c0ef16ce209e4037a02a49e8b69e8065896c8d76731460dba182",
        note = "小样本，xz --check=crc32（与默认 crc64 走不同的 check 分支）",
    )

    /** xz --block-size=16384 压出来的多块流 —— **已知缺陷的钉子**：内置解码器解不动它，见 XzDecoderTest（解码后 73600 字节） */
    val MULTI_BLOCK = Vector(
        name = "multi",
        compressedB64 =
            "/Td6WFoAAATm1rRGAgAhARwAAAAQz1jM4D//AThdADEbCkIhsEDQepPEBNepvDABWSsKLCIthqdOyMugw7NjeXD5+tEs02ZFPdHf" +
            "PlSZlOYnJk68fqyXuxvjAQYPx02MtmQvL1waoQ1yfkTBPEBJHxhHfWCTfX7SEoELto2cA5u+FLKvaczJkukz96WN005tyZ+KxQ35" +
            "fdfcQTFuXZbJx4nImF0dYwxRCBCEvARdd+SY3lx6H+t5K1jIgUpp55B2VLD5z89y3CYcrTP/thbrkoY5Lwja5Ia0KrCidjZJYaHE" +
            "UXDEAk5K0j/nJ/jBzfyOBZRHLfQv4MrDA/blbYLtnE/SfMCCw6Uw6yeemfXBZ7OpxoFmNMTsyeHxNKzWI5t1rIaBf24slKF2tM5V" +
            "9ux5GzgGyCcxcp/WfQ8SLOFOMNQxHSoc8IeFvKcKLJWklJLYPVGLLBAAAACbWhcsC+8WNgIAIQEcAAAAEM9YzOA//wEhXQAYDMMB" +
            "ShBbt5JNmf9qbmqM9acsQOd0SREjhVYDaNRAQ7caO3DzHyAVu4X4fblWyiDN8V0XBzaoPBQdoy34kd3xJ0ANFWwLakbzXttk77xS" +
            "1tDzBsE9vnmFvmd8JGFg7bCTyyFNt5lL2ngQc+Y1VUJfJqX8SKOeDVV6p6SPmx6QqHKyTTOHAldCHPpJM9uTVtWNLxAQmecqU8+j" +
            "LaCaWK8889B2Cb4hDcqBlU4BAKiT89Ve5aGDLpavMxtHFl4qHJVPfcmgnNALpfTFbIdqdMr91yj/f2cnE//uoH47oIEzF0iENsQG" +
            "P76zSGenvgbTC35ZnLaWm/kNg5oeI80k3rCNOMIOBMa3O1V6hjV+/GEfbIttZG3B1bT9hv6OkywNhDncAAAAAC78Js83jHAMAgAh" +
            "ARwAAAAQz1jM4D//ATtdADqUifh0EDwBj/tevIju5pJ/J8sBuPiNKQhVvwqp5AnZt76m8GuYHhtmn4enkrOjp1nX2AOXceu24D5+" +
            "aaH5ivupm8FeFs4Z+PXLQRqFvh7C23ZA3M7PqqqIO/yKl+Uvpd6K+dQ6veOM/h5pkHuoKHDkapIgkdAC/LIzHnDBK221Aa7GE5wM" +
            "rEUJ9rmeHCz6cqwgyVc1yuHzDg+oWIknIbQvABSVNNy4yN0tUSK34XaUigo0Hfc5lEIGdhYHKZkqtcs0E5azelO3W+/jLmlj1m49" +
            "IjIuUfyQFqHm/ItABRAPcCgqdC42A+2dJbZeHN4qsdwPhm9NMmCBlP53g/lAidXZFYPFfZg3orVhicF7duJ+FIfUA6M2EizpkEP2" +
            "KrgWaa+x5zrrQ8108RnbzlftkLp1dXFdeFJ8W4xoAAAA56fYLMfMaIgCACEBHAAAABDPWMzgP/8Bal0AMIgKxpPJIowk8B7ELdFF" +
            "UW4+EyhBvzfReLugjDjsjX515MR+axBC7H6PGNMABCMF3WWdw49s4x8aZRKSZEftjZgs7QQcMc11203A3e10pj2Qh00m7phQlCM+" +
            "Ly3KequKvnByHjpYGksBjzeZvysa2I6uKwWWBNUQaEvQnWTlc/3yG1gBKb/6b8NwcC+UwzSwZMQogITOnAetKHOodKWhPYQ0dSEU" +
            "EsG/65jo1qQMtVv3QFGdJRxK/3HZ8Z/6D0vMZn4CCV9hbhM028Iavl5tmsfiqFauCwlhZgp6+/zvzFnNpNB2+VvFgoVQMppkkyWI" +
            "dVi3aQ/dd7rgW3A7kapHIgXFV93tPnG43H6Rm4hjWUT/e1pRNg8nlmOUgSvWAbWle8JSfjJozHUJcss6rINOV6n5BgXZHhh4y02Y" +
            "RoG/0mwygLQxTA1j4XoT/0yfCVEQetN4oYGO7wAjv2r36xbXge6XdQXDPA3LAAAAAAC2RygSFU7mIgIAIQEcAAAAEM9YzOAffwDY" +
            "XQAQHAgnzNc7/QW4P3fCOnb9oWBcZok0aETAniYGvVtEwKxAHRzJdOOgoG/u0WTDTrMja8i7aPFYSHrGOZnnqH2bsHL8W6VkyXnT" +
            "9bV0FMliQF4/iUN/O1MkyDSbMKDr8Zg/OqUOuxoLkARGv+T020+ZYlR2rP+QUrlvlrU6wIymxiydkPe2sYolAoD1fDiU5BLEsLzB" +
            "aygDNa2P3fuKXNM4YJZ0uOtuZvlsbPE2aebfmpxsEOxC8e8BmiTHeoAOes31LSnXpwIJ9eFk14O9DTb1SwTbIkhgkAAASSlo/mDX" +
            "+jMABdQCgIABvQKAgAHXAoCAAYYDgIAB9AGAPwAAGwFLiAJVqqsHAAAAAARZWg=="
            ,
        decodedSize = 73600,
        decodedSha256 = "c1c2cc578a21e88ff849df909d9b5adb56c7f80bf9e0ab15b0a341fe4b0fc838",
        note = "xz --block-size=16384 压出来的多块流（用来验证「截断后能解出前面完整的块」）",
    )

    /** 三条**能正确解开**的向量（MULTI_BLOCK 不在里面，它是已知缺陷的钉子）。 */
    val ALL = listOf(MIXED, REPETITIVE, CRC32_CHECK)
}
