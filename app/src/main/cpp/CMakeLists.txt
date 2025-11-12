cmake_minimum_required(VERSION 3.22.1)

project("sonicsphere")

# Configurar C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Adicionar Oboe
set(OBOE_DIR "C:/Users/RDP/StudioProjects/SurroundPlayer-3D/oboe")
add_subdirectory(${OBOE_DIR} ./oboe)

# Incluir diretórios
include_directories(${OBOE_DIR}/include)

# Criar biblioteca nativa
add_library(
        sonicsphere
        SHARED
        native-lib.cpp
        AudioEngine.cpp
        HaasProcessor.cpp
)

# Linkar bibliotecas
target_link_libraries(
        sonicsphere
        oboe
        log
        android
)