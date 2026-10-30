package com.example.homeezch.ai

/**
 * On-device models for EVA's brain. Sizes and SHA-256 were checked by downloading each file from the owner's
 * home network (claude-build-logs/r22-models.json, 26.10.2026: 9–17 МБ/с from huggingface.co).
 */
internal object EvaModelCatalog {
    val MODELS = listOf(
        EvaModels.Model("vikhr-qwen-1.5b", "Ева · умная (русская, 1,5 млрд)", "Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf",
            "https://huggingface.co/Vikhrmodels/Vikhr-Qwen-2.5-1.5B-Instruct-GGUF/resolve/main/Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf",
            985_700_992L, "d82b16244a33dbbde4742e38e42b2b3d74dd883f4b8ff4ae53eca29aabf60662",
            "Дообучена на русском (Vikhr). Лучше всех говорит по-русски. Для ТВ с 2 ГБ памяти и больше."),
        EvaModels.Model("qwen-0.5b", "Ева · быстрая (0,5 млрд)", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491_400_032L, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            "В 3 раза быстрее, но проще и чаще ошибается. Для слабых телевизоров."),
        EvaModels.Model("gemma3-1b", "Ева · Gemma (1 млрд)", "gemma-3-1b-it-Q4_K_M.gguf",
            "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            806_058_240L, "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
            "Модель Google. Запасной вариант, если «умная» отвечает медленно.")
    )
}
