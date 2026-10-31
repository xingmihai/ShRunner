# 内置 PTY 工具 shpty：不依赖系统的 script / busybox。
# 以可执行文件形式产出（命名为 libshpty.so 放在 lib/<abi>/ 下，
# 这样 APK 安装后即位于 nativeLibraryDir，天然带可执行权限）。

cmake_minimum_required(VERSION 3.22.0)
project(shpty C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_executable(shpty shpty.c)

# 输出成 libshpty.so，让 AGP 按 ABI 打包进 lib/<abi>/
set_target_properties(shpty PROPERTIES
        OUTPUT_NAME "shpty"
        PREFIX "lib"
        SUFFIX ".so"
        # Android 要求可执行文件为 PIE
        POSITION_INDEPENDENT_CODE ON
)

target_link_options(shpty PRIVATE "-pie")

# 精简体积，去掉不需要的段
target_compile_options(shpty PRIVATE -Os -ffunction-sections -fdata-sections)
target_link_options(shpty PRIVATE -Wl,--gc-sections)
