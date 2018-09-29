function createEnum(enumObj) {
    const enumHandler = {
        get: function(target, name) {
            if (target[name]) {
                return target[name]
            }
            throw new Error(`No such enumerator: ${name}`)
        }
    }

    return new Proxy(Object.freeze(enumObj), enumHandler)
}

const remainingTimeScope = createEnum({
    TASK: "TASK",
    GLOBAL: "GLOBAL"
})


