package tech.kotlin.common.mysql

import org.apache.ibatis.annotations.*
import org.apache.ibatis.io.Resources
import org.apache.ibatis.jdbc.ScriptRunner
import org.apache.ibatis.session.SqlSession
import org.apache.ibatis.session.SqlSessionFactory
import org.apache.ibatis.session.SqlSessionFactoryBuilder
import tech.kotlin.common.os.Abort
import tech.kotlin.common.os.Log
import tech.kotlin.common.utils.abort
import tech.kotlin.service.Err
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import kotlin.reflect.KClass

/*********************************************************************
 * Created by chpengzh@foxmail.com
 * Copyright (c) http://chpengzh.com - All Rights Reserved
 *********************************************************************/
object Mysql {

    lateinit var sqlSessionFactory: SqlSessionFactory

    fun init(config: String, properties: Properties, sql: String = "") {
        val inputStream = Resources.getResourceAsStream(config)
        sqlSessionFactory = SqlSessionFactoryBuilder().build(inputStream, properties)

        if (sql.isNullOrBlank()) return

        write { session ->
            // Initialize object for ScriptRunner
            val runner = ScriptRunner(session.connection)
            // Give the input file to Reader
            BufferedReader(InputStreamReader(javaClass.classLoader.getResourceAsStream(sql.trimStart('/')))).use {
                runner.runScript(it)
            }
        }
    }

    fun register(mapper: Class<*>) {
        sqlSessionFactory.configuration.addMapper(mapper)
    }

    inline fun <T> read(defaultError: Err = Err.SYSTEM, action: (SqlSession) -> T): T {
        try {
            return sqlSessionFactory.openSession(true).use(action)
        } catch (error: Abort) {
            throw error
        } catch (error: Exception) {
            Log.e(error)
            abort(defaultError)
        }
    }

    inline fun <T> write(defaultError: Err = Err.SYSTEM, action: (SqlSession) -> T): T {
        sqlSessionFactory.openSession(false).use {
            try {
                val result = action(it)
                it.commit()
                return result
            } catch (error: Abort) {
                it.rollback()
                throw error
            } catch (error: Exception) {
                it.rollback()
                Log.e(error)
                abort(defaultError)
            }
        }
    }
}

//为 Mybatis ORM 添加自定义的日志
operator fun <T : Any> SqlSession.get(kClass: KClass<T>): T {
    val clazz = kClass.java
    val mapper = getMapper(clazz)
    @Suppress("UNCHECKED_CAST")
    return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, kotlin.arrayOf(clazz)) { _, method, args ->
        try {
            val sqlGen: (KClass<*>, String) -> String = { clazz, methodName ->
                val generator = clazz.java.newInstance()
                val provider = clazz.java.getMethod(methodName, *method.parameterTypes)
                provider(generator, *args) as String
            }
            when {
                method.isAnnotationPresent(Select::class.java) ->
                    tech.kotlin.common.os.Log.d("SQL", method.getAnnotation(Select::class.java).value[0].trimIndent().replace("\n", " "))
                method.isAnnotationPresent(Insert::class.java) ->
                    tech.kotlin.common.os.Log.d("SQL", method.getAnnotation(Insert::class.java).value[0].trimIndent().replace("\n", " "))
                method.isAnnotationPresent(Update::class.java) ->
                    tech.kotlin.common.os.Log.d("SQL", method.getAnnotation(Update::class.java).value[0].trimIndent().replace("\n", " "))
                method.isAnnotationPresent(Delete::class.java) ->
                    tech.kotlin.common.os.Log.d("SQL", method.getAnnotation(Delete::class.java).value[0].trimIndent().replace("\n", " "))
                method.isAnnotationPresent(SelectProvider::class.java) -> {
                    val annotation = method.getAnnotation(SelectProvider::class.java)
                    val sql = sqlGen(annotation.type, annotation.method)
                    tech.kotlin.common.os.Log.d("SQL", sql.trimIndent().replace("\n", " "))
                }
                method.isAnnotationPresent(InsertProvider::class.java) -> {
                    val annotation = method.getAnnotation(InsertProvider::class.java)
                    val sql = sqlGen(annotation.type, annotation.method)
                    tech.kotlin.common.os.Log.d("SQL", sql.trimIndent().replace("\n", " "))
                }
                method.isAnnotationPresent(UpdateProvider::class.java) -> {
                    val annotation = method.getAnnotation(UpdateProvider::class.java)
                    val sql = sqlGen(annotation.type, annotation.method)
                    tech.kotlin.common.os.Log.d("SQL", sql.trimIndent().replace("\n", " "))
                }
                method.isAnnotationPresent(DeleteProvider::class.java) -> {
                    val annotation = method.getAnnotation(DeleteProvider::class.java)
                    val sql = sqlGen(annotation.type, annotation.method)
                    tech.kotlin.common.os.Log.d("SQL", sql.trimIndent().replace("\n", " "))
                }
            }
        } catch (err: Throwable) {
            tech.kotlin.common.os.Log.e(err)
        }
        if (args == null || args.isEmpty()) {
            method.invoke(mapper)
        } else {
            tech.kotlin.common.os.Log.d("SQL", "args:${tech.kotlin.common.serialize.Json.dumps(args)}")
            method.invoke(mapper, *args)
        }
    } as T
}