rootProject.name = "convoy"

include("libs:price-engine", "libs:contracts", "libs:platform")
include("services:identity", "services:gateway", "services:catalog", "services:dealer", "services:payment", "services:pool", "services:quote", "services:order", "services:realtime")
