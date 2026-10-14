package org.example.ykplugin.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwaggerParserTest {

    private val parser = SwaggerParser()

    @Test
    fun ignoresClientErrorResponseSchemas() {
        val model = parser.parse(
            """
            {
              "openapi": "3.0.1",
              "info": { "title": "Example" },
              "paths": {
                "/items": {
                  "post": {
                    "operationId": "createItem",
                    "responses": {
                      "400": {
                        "description": "Bad request",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "message": { "type": "string" }
                              }
                            }
                          }
                        }
                      },
                      "422": {
                        "description": "Validation error",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "field": { "type": "string" }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        assertNull(model.endpoints.single().responseSchema)
    }

    @Test
    fun prefersSuccessResponseSchemaOverErrorResponseSchemas() {
        val model = parser.parse(
            """
            {
              "openapi": "3.0.1",
              "info": { "title": "Example" },
              "paths": {
                "/items/{id}": {
                  "get": {
                    "operationId": "getItem",
                    "responses": {
                      "400": {
                        "description": "Bad request",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "message": { "type": "string" }
                              }
                            }
                          }
                        }
                      },
                      "200": {
                        "description": "OK",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object",
                              "properties": {
                                "id": { "type": "string" }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val responseSchema = model.endpoints.single().responseSchema
        assertEquals("object", responseSchema?.type)
        assertEquals("string", responseSchema?.properties?.get("id")?.type)
        assertNull(responseSchema?.properties?.get("message"))
    }

    @Test
    fun treatsEmptyRootSchemasAsMissingBodySchemas() {
        val model = parser.parse(
            """
            {
              "openapi": "3.0.1",
              "info": { "title": "Example" },
              "paths": {
                "/ping": {
                  "post": {
                    "operationId": "ping",
                    "requestBody": {
                      "content": {
                        "application/json": {
                          "schema": {}
                        }
                      }
                    },
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": {
                          "application/json": {
                            "schema": {
                              "type": "object"
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val endpoint = model.endpoints.single()
        assertNull(endpoint.requestSchema)
        assertNull(endpoint.responseSchema)
    }
}
